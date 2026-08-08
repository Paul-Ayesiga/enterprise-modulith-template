package ug.co.smsone.search.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.search.SearchDoc;

/**
 * The projection's write side, plain JDBC like the other hot projection paths: one atomic upsert
 * per document — the {@code (entity_type, entity_id)} conflict target is what makes at-least-once
 * feeders idempotent, and the generated {@code tsv} column re-derives on every update.
 */
@Component
class SearchIndexStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    SearchIndexStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void upsert(SearchDoc doc) {
        jdbc.update("""
                insert into platform.search_document (id, org_id, entity_type, entity_id, title, body, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (entity_type, entity_id) do update
                    set org_id = excluded.org_id, title = excluded.title,
                        body = excluded.body, updated_at = excluded.updated_at
                """, UUID.randomUUID(), doc.orgId(), doc.entityType(), doc.entityId(),
                doc.title(), doc.body(), Timestamp.from(clock.instant()));
    }

    void remove(String entityType, String entityId) {
        jdbc.update("delete from platform.search_document where entity_type = ? and entity_id = ?",
                entityType, entityId);
    }
}
