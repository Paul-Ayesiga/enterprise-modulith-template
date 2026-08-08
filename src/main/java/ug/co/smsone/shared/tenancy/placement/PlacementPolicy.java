package ug.co.smsone.shared.tenancy.placement;

import java.util.UUID;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * Where a NEW tenant is born (ADR 0010 §1, §4.3). One flag, two answers, and the difference between
 * them is the whole of what ADR 0010 decided — so it is a switch the owner can flip and measure
 * rather than a number to take on faith.
 *
 * <p><strong>{@link #SILO_PER_ORG} is the default: one organization, one schema, decided at creation.</strong>
 * That is the owner's decision, taken on 2026-08-08 against the re-measurement below. It is also the
 * design that makes {@code pg_dump -n t_<hex>} a complete tenant with no {@code WHERE org_id = ?}
 * predicate to get wrong — the property ADR 0010 exists to buy, now available to every tenant on the
 * day it is created rather than to whichever ones an operator remembered to promote.
 *
 * <p><strong>ADR 0010 §1 made {@link #POOLED} the default on four measurements, and the
 * re-measurement (§8 Q1, against 200 real silos holding 320,000 tickets) found all four wrong.</strong>
 * The history is kept because the shape of the error is the useful part:
 *
 * <ul>
 *   <li><strong>The lock wall was the only real blocker, and it was an artefact of the query shape.</strong>
 *       §1 measured one {@code UNION} branch per tenant, where locks accumulate for the life of the
 *       transaction. What shipped is {@code TenantHomeSweep} / {@code TenantFanOut}: one statement per
 *       home, each releasing its own locks. Measured at <strong>9 lock entries at any instant, flat
 *       from 1 home to 200</strong>. There is nothing left to exhaust.</li>
 *   <li><strong>Planning: 0.024–0.044 ms per branch warm, not 0.5–0.66</strong>, and it amortizes both
 *       per backend and again under a server-side prepared statement. §1's figure was a cold-backend
 *       first execution.</li>
 *   <li><strong>Footprint: 126 relations and 912 KB per silo, not 239 and 3.2 MB</strong> — §1 quoted
 *       the uniform count (every table in every schema) for a tenant tier that is 28 tables. At 5,000
 *       tenants that is ~4.35 GB of empty files and ~2.65 GB of catalog, against the ~16 GB and 5–6 GB
 *       argued. Real, and it is disk.</li>
 *   <li><strong>JDBC metadata: 287–527 ms across 201 schemas, not ~9 s</strong> — and moot either way,
 *       because {@code ddl-auto} is {@code none} and {@code MappedSchemaValidator} issues no unfiltered
 *       metadata call at all.</li>
 * </ul>
 *
 * <p><strong>The cost that IS real, and it is none of the four.</strong> The per-home merge is ~1.29–1.39 ms
 * per home, flat — so a cross-tenant OPERATOR listing costs ~279 ms per page at 200 tenants and scales
 * linearly past it. That degrades platform-wide admin surfaces ({@code TicketFanOut}, and
 * {@code SlaEscalationJob} paying the same shape once a minute); it does not touch tenant-facing
 * traffic, which is pinned to one schema and never fans out. The designed answer is §8 Q2's
 * {@code platform.ticket_index} projection, and this default moves that from "on a trigger, at 50
 * silos" to the critical path — a projection ships with its reconciler or it does not ship.
 *
 * <p><strong>What this default is NOT.</strong> It is not an isolation feature. Schema separation inside
 * one cluster buys almost nothing in security terms — the same role reaches every schema (§1) — and the
 * guarantees this system actually has live in {@code ApiPermissionEvaluator} and {@code OrgAuthorization},
 * unchanged by which value is set. Every {@code org_id} column and every {@code org_id} predicate stays
 * regardless (§1): in a silo the predicate is redundant but free, and it is the detector that catches a
 * {@code search_path} mistake, which is the worst failure this design can produce.
 *
 * <p>{@link #POOLED} remains fully supported and is the right answer for a deployment expecting many
 * thousands of small tenants, where the disk and the operator-listing curve both bite. Nothing about the
 * pooled path was removed: {@code tenant_pool} still exists, still migrates, and promotion still moves a
 * tenant out of it.
 */
public enum PlacementPolicy {

    /**
     * Every new tenant lands in the shared {@code tenant_pool}, which already exists.
     *
     * <p>ADR 0010 §4.3 calls this the strongest practical argument for the hybrid, and the reason is
     * an ordering hazard that simply does not arise here: signup creates no DDL, so nothing has to
     * happen before {@code OrganizationRegistered} is published and the registration transaction is
     * exactly what it was.
     */
    POOLED,

    /**
     * Every new tenant gets its own {@code t_<32hex>} schema, created and migrated before the tenant
     * is announced.
     *
     * <p><strong>The ordering is the point.</strong> Three {@code @ApplicationModuleListener}s hang
     * off {@code OrganizationRegistered} — the trial, the billing account and the search document —
     * and all three fire AFTER COMMIT, asynchronously, against tenant-tier tables. Create the schema
     * after the commit and all three race it; every new tenant silently gets no trial and no billing
     * account, and because they are outbox-retried the failures look like transient noise rather than
     * a broken signup (§4.3). {@link TenantProvisioner} is where that ordering is enforced.
     */
    SILO_PER_ORG;

    /**
     * The schema a tenant with this id is born into. No database read — a policy decision must be
     * answerable before the row that would answer it exists.
     */
    public String homeFor(UUID orgId) {
        return this == POOLED ? TenantSchemas.TENANT_POOL : TenantSchemas.siloSchema(orgId);
    }

    /**
     * Whether creating a tenant has to build something before the tenant may be announced — the
     * question ADR 0010 §4.3 turns on, asked in the words of the rule rather than in the words of the
     * flag. False for {@link #POOLED} (the home already exists), true for {@link #SILO_PER_ORG}.
     */
    public boolean buildsASchema() {
        return this != POOLED;
    }
}
