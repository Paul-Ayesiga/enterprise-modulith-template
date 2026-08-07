package ug.co.smsone.geo.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.geo.GeoSource;
import ug.co.smsone.shared.geo.GeoStamp;
import ug.co.smsone.shared.geo.PlaceLabel;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.Cursors;

/**
 * Phase 1 spatial impl: a bounding-box filter over the {@code (org_id, latitude, longitude)} B-tree
 * index, keyset-paginated by {@code (captured_at desc, id desc)}. All of geo's coordinate SQL lives
 * here — the single seam a future {@code PostgisGeoSearch} would replace. Native SQL, so it filters
 * {@code deleted_at} itself (Hibernate's {@code @SQLRestriction} does not reach a JdbcTemplate query).
 * The cursor carries the sort keys as an ISO instant + uuid, so a cursor minted for another collection
 * is rejected as a 4xx rather than blowing up mid-query.
 */
@Component
class HaversineGeoSearch implements GeoSearch {

    private final JdbcTemplate jdbc;

    HaversineGeoSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page search(Query query, CursorPageRequest page) {
        Cursor cursor = decode(page);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("org_id = ? and deleted_at is null");
        params.add(query.orgId());
        if (query.subjectType() != null && !query.subjectType().isBlank()) {
            where.append(" and subject_type = ?");
            params.add(query.subjectType().trim());
        }
        if (query.subjectId() != null && !query.subjectId().isBlank()) {
            where.append(" and subject_id = ?");
            params.add(query.subjectId().trim());
        }
        if (query.hasBoundingBox()) {
            where.append(" and latitude between ? and ? and longitude between ? and ?");
            params.add(query.minLat());
            params.add(query.maxLat());
            params.add(query.minLng());
            params.add(query.maxLng());
        }
        if (cursor != null) {
            // Row-value keyset for the (captured_at desc, id desc) order: strictly "before" the last row.
            where.append(" and (captured_at, id) < (?, ?)");
            params.add(Timestamp.from(cursor.capturedAt()));
            params.add(cursor.id());
        }
        params.add(page.size() + 1); // fetch one extra to know whether a next page exists

        String sql = "select id, org_id, subject_type, subject_id, latitude, longitude, accuracy_m, "
                + "altitude_m, source, captured_by_person_id, captured_at, consent_ref, country_code, admin1, "
                + "locality, formatted_address from geo_stamp where " + where
                + " order by captured_at desc, id desc limit ?";
        List<GeoStamp> rows = jdbc.query(sql, HaversineGeoSearch::mapRow, params.toArray());

        boolean hasMore = rows.size() > page.size();
        List<GeoStamp> pageRows = hasMore ? rows.subList(0, page.size()) : rows;
        String nextCursor = null;
        if (hasMore) {
            GeoStamp last = pageRows.get(pageRows.size() - 1);
            Map<String, Object> keys = new LinkedHashMap<>();
            keys.put("t", last.capturedAt().toString());
            keys.put("id", last.id());
            nextCursor = Cursors.encode(ScrollPosition.forward(keys));
        }
        return new Page(List.copyOf(pageRows), hasMore, nextCursor);
    }

    private static GeoStamp mapRow(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal accuracy = rs.getBigDecimal("accuracy_m");
        BigDecimal altitude = rs.getBigDecimal("altitude_m");
        return new GeoStamp(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                rs.getBigDecimal("latitude").doubleValue(),
                rs.getBigDecimal("longitude").doubleValue(),
                accuracy == null ? null : accuracy.doubleValue(),
                altitude == null ? null : altitude.doubleValue(),
                GeoSource.valueOf(rs.getString("source")),
                rs.getObject("captured_by_person_id", UUID.class),
                rs.getTimestamp("captured_at").toInstant(),
                rs.getString("consent_ref"),
                new PlaceLabel(rs.getString("country_code"), rs.getString("admin1"),
                        rs.getString("locality"), rs.getString("formatted_address")));
    }

    private record Cursor(Instant capturedAt, UUID id) {
    }

    private static Cursor decode(CursorPageRequest page) {
        KeysetScrollPosition position = page.scrollPosition();
        Map<String, Object> keys = position.getKeys();
        if (keys.isEmpty()) {
            return null;
        }
        if (keys.size() != 2 || !(keys.get("t") instanceof String t) || !(keys.get("id") instanceof UUID id)) {
            throw new ValidationException("page[after] is not a valid cursor for this collection.",
                    ApiSource.parameter("page[after]"));
        }
        try {
            return new Cursor(Instant.parse(t), id);
        } catch (DateTimeParseException ex) {
            throw new ValidationException("page[after] is not a valid cursor for this collection.",
                    ApiSource.parameter("page[after]"));
        }
    }
}
