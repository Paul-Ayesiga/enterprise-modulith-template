package ug.co.smsone.search.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.search.SearchDoc;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;

/**
 * The projection's write side, plain JDBC like the other hot projection paths: one atomic upsert
 * per document — the {@code (entity_type, entity_id)} conflict target is what makes at-least-once
 * feeders idempotent, and the generated {@code tsv} column re-derives on every update.
 *
 * <h2>ADR 0011: this table is platform-tier and HALF its writers are not on the platform axis</h2>
 *
 * <p>The two feeders in {@code SearchEventListeners} are {@code @ApplicationModuleListener}s, so
 * {@code AsyncConfig}'s {@code TaskDecorator} runs them on the platform axis and they have always
 * reached this table correctly. <strong>The {@code SearchIndex} PORT is the other half</strong>, and it
 * is called at the producer's hand from inside the producer's own request and transaction —
 * {@code DocumentService.upload} is the reference producer and it is {@code @Transactional} on the
 * caller's axis, which on any org route is that organization's. So this insert has been issued on the
 * TENANT's connection while naming a schema that, since the router shipped, may be in another database
 * entirely. There it is {@code relation "platform.search_document" does not exist}: not a missing search
 * hit, a 500 that rolls back the upload that would have produced it.
 *
 * <p>{@link CrossDatabaseWrites#runOnPlatform} is the conversion, and it is the whole of it — a no-op
 * (same connection, same transaction) for every caller already co-located with primary, which is every
 * caller on every deployment with no remote datasource configured, and a separate borrow from the
 * primary pool for a remote tenant.
 *
 * <p><strong>What the hop costs, named here rather than discovered as a support ticket.</strong> For a
 * remote tenant the index row is a separate transaction: it commits even if the upload that produced it
 * later rolls back, leaving a search hit pointing at a document that does not exist. That direction is
 * the survivable one and it already has a sweeper — {@code SoftDeletePurgeJob.sweepSearchResidue} exists
 * precisely because this projection accumulates rows whose source is gone. The opposite trade (index
 * inside the tenant's transaction, i.e. not at all) would be a document nothing can find, with nothing
 * anywhere looking for it.
 */
@Component
class SearchIndexStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CrossDatabaseWrites platformTier;

    SearchIndexStore(JdbcTemplate jdbc, Clock clock, CrossDatabaseWrites platformTier) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.platformTier = platformTier;
    }

    void upsert(SearchDoc doc) {
        platformTier.runOnPlatform(() -> jdbc.update("""
                insert into platform.search_document (id, org_id, entity_type, entity_id, title, body, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (entity_type, entity_id) do update
                    set org_id = excluded.org_id, title = excluded.title,
                        body = excluded.body, updated_at = excluded.updated_at
                """, UUID.randomUUID(), doc.orgId(), doc.entityType(), doc.entityId(),
                doc.title(), doc.body(), Timestamp.from(clock.instant())));
    }

    void remove(String entityType, String entityId) {
        platformTier.runOnPlatform(() -> jdbc.update(
                "delete from platform.search_document where entity_type = ? and entity_id = ?",
                entityType, entityId));
    }
}
