package ug.co.smsone.search.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.Cursors;
import ug.co.smsone.shared.web.PageMeta;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The query side: FTS first ({@code websearch_to_tsquery} ranked by {@code ts_rank_cd}), trigram
 * similarity over titles as the fallback when the tsquery matches nothing (prefixes, typos). Keyset
 * pagination over {@code (rank desc, id desc)} — the cursor carries the mode, so page 2 continues
 * the strategy page 1 chose instead of re-deciding. Snippets are computed only for the returned
 * page ({@code ts_headline} on the outer select), never for every match.
 */
@Service
@Transactional(readOnly = true)
class SearchQueryService {

    record SearchHit(UUID id, UUID orgId, String entityType, String entityId, String title,
            String snippet, double rank) {
    }

    private static final int MAX_QUERY_LENGTH = 200;
    private static final String MODE_FTS = "fts";
    private static final String MODE_TRGM = "trgm";

    private final JdbcTemplate jdbc;

    SearchQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    <T> WindowedResult<T> search(UUID orgScope, boolean platformWide, String type, String q,
            CursorPageRequest page, java.util.function.Function<SearchHit, T> mapper) {
        String query = requireQuery(q);
        Cursor cursor = decode(page);

        String mode = cursor == null ? MODE_FTS : cursor.mode();
        List<SearchHit> hits = run(mode, orgScope, platformWide, type, query, cursor, page.size() + 1);
        if (cursor == null && hits.isEmpty() && query.length() >= 2) {
            mode = MODE_TRGM; // nothing token-matched — fall back to fuzzy titles for this search
            hits = run(mode, orgScope, platformWide, type, query, null, page.size() + 1);
        }

        boolean hasMore = hits.size() > page.size();
        List<SearchHit> pageHits = hasMore ? hits.subList(0, page.size()) : hits;
        String nextCursor = null;
        if (hasMore) {
            SearchHit last = pageHits.getLast();
            Map<String, Object> keys = new LinkedHashMap<>();
            keys.put("rank", last.rank());
            keys.put("id", last.id());
            keys.put("mode", mode);
            nextCursor = Cursors.encode(
                    (KeysetScrollPosition) org.springframework.data.domain.ScrollPosition.forward(keys));
        }
        List<T> items = pageHits.stream().map(mapper).toList();
        return new WindowedResult<>(items, new PageMeta(page.size(), items.size(), hasMore, nextCursor));
    }

    private List<SearchHit> run(String mode, UUID orgScope, boolean platformWide, String type,
            String q, Cursor cursor, int limit) {
        // PORTING: native Postgres full-text search (websearch_to_tsquery / ts_rank_cd) + pg_trgm
        // word_similarity fallback. No portable equivalent — a non-Postgres product swaps the search
        // adapter for an external engine (OpenSearch/Elasticsearch). See docs/PORTING.md.
        boolean fts = MODE_FTS.equals(mode);
        // Params are appended in the placeholders' TEXTUAL order — JDBC binds positionally, and the
        // two modes differ: FTS's first ? is the tsquery in the FROM clause; trigram's is the
        // similarity() in the SELECT list, before the % in the WHERE.
        List<Object> params = new java.util.ArrayList<>();
        // word_similarity, not similarity: it scores the query against the BEST-matching word, so a
        // prefix ("reconcil") or a one-word typo still clears the threshold instead of being diluted
        // by the rest of the title. The ::float8 cast is load-bearing for the keyset: both rank
        // functions return float4, whose shortest text form parses into a DIFFERENT double on the
        // JDBC side — the cursor's rank would then sit strictly above every equal-ranked row and
        // page 2 would repeat page 1. Cast once and the round-trip is exact.
        String rankExpr = fts
                ? "cast(ts_rank_cd(d.tsv, query) as float8)"
                : "cast(word_similarity(?, d.title) as float8)";
        if (fts) {
            params.add(q); // FROM: websearch_to_tsquery('simple', ?)
        } else {
            params.add(q); // SELECT: word_similarity(?, d.title)
        }

        StringBuilder where = new StringBuilder(fts ? "d.tsv @@ query" : "? <% d.title");
        if (!fts) {
            params.add(q); // WHERE: ? <% d.title
        }
        // Tenant search ALWAYS filters by the caller's org (the cut is in the query, §5.6); the
        // platform search filters only when the operator narrowed it.
        if (!platformWide || orgScope != null) {
            where.append(" and d.org_id = ?");
            params.add(orgScope);
        }
        if (type != null && !type.isBlank()) {
            where.append(" and d.entity_type = ?");
            params.add(type.trim());
        }
        if (cursor != null) {
            where.append(" and (").append(rankExpr).append(", d.id) < (?, ?)");
            if (!fts) {
                params.add(q); // the keyset predicate repeats word_similarity(?, d.title)
            }
            params.add(cursor.rank());
            params.add(cursor.id());
        }

        String from = fts
                ? "search_document d, websearch_to_tsquery('simple', ?) query"
                : "search_document d";
        String headline = fts
                ? "ts_headline('simple', hit.body, hit.query, 'MaxFragments=1, MaxWords=18, MinWords=6')"
                : "left(hit.body, 160)";
        String inner = "select d.id, d.org_id, d.entity_type, d.entity_id, d.title, d.body, "
                + (fts ? "query, " : "")
                + rankExpr + " as rank from " + from + " where " + where
                + " order by rank desc, d.id desc limit ?";
        params.add(limit);
        String sql = "select hit.id, hit.org_id, hit.entity_type, hit.entity_id, hit.title, "
                + headline + " as snippet, hit.rank from (" + inner + ") hit "
                + "order by hit.rank desc, hit.id desc";
        return jdbc.query(sql, (rs, n) -> new SearchHit(
                        rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("title"),
                        rs.getString("snippet"),
                        rs.getDouble("rank")),
                params.toArray());
    }

    private static String requireQuery(String q) {
        if (q == null || q.isBlank()) {
            throw new ValidationException("q must not be blank.", ApiSource.parameter("q"));
        }
        String trimmed = q.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new ValidationException("q must be at most " + MAX_QUERY_LENGTH + " characters.",
                    ApiSource.parameter("q"));
        }
        return trimmed;
    }

    private record Cursor(double rank, UUID id, String mode) {
    }

    /** Manual key validation — this collection's keys are synthetic (rank/id/mode), not a Sort. */
    private static Cursor decode(CursorPageRequest page) {
        KeysetScrollPosition position = page.scrollPosition();
        if (position.getKeys().isEmpty()) {
            return null;
        }
        Map<String, Object> keys = position.getKeys();
        if (!(keys.get("rank") instanceof Double rank) || !(keys.get("id") instanceof UUID id)
                || !(keys.get("mode") instanceof String mode)
                || !(MODE_FTS.equals(mode) || MODE_TRGM.equals(mode)) || keys.size() != 3) {
            throw new ValidationException("page[after] is not a valid cursor for this collection.",
                    ApiSource.parameter("page[after]"));
        }
        return new Cursor(rank, id, mode);
    }
}
