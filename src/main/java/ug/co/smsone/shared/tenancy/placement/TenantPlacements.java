package ug.co.smsone.shared.tenancy.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * Every statement there is against {@code platform.tenant_placement} (ADR 0010 §4.2). The registry is
 * small enough that keeping all of it in one class is not organisation for its own sake — it is what
 * makes "which writes can change a tenant's home, and under what condition" a question with one file
 * for an answer.
 *
 * <p><strong>Every statement names {@code platform.} and none of them pins an axis.</strong> That is
 * required rather than tidy: this table is read on the request path before a tenant has been decided
 * (§4.4's floor check) and written from inside a tenant-pinned transaction (the announcement below),
 * so it has to resolve identically from either. A qualified name always does. AGENTS §1 states the
 * same rule as a build gate — {@code PlatformSchemaQualificationTest} fails on a bare reference here.
 *
 * <h2>Two conditional writes, and the conditions are the design</h2>
 *
 * <p>{@link #announce} and {@link #reserve} are both upserts whose {@code ON CONFLICT … WHERE} clause
 * refuses to touch an ACTIVE row. They are the reason this class exposes no general "save" and no
 * setter for {@code state}:
 *
 * <ul>
 *   <li>{@link #announce} answers <em>"am I the call that gets to publish
 *       {@code OrganizationRegistered} for this tenant?"</em> — and answers it with the row count of a
 *       single statement, so two concurrent creates cannot both get a yes. The loser blocks on the row
 *       lock and then sees ACTIVE.</li>
 *   <li>{@link #reserve} claims a home for a tenant that has none yet, and <strong>declines to move a
 *       tenant that is already serving</strong>. Moving one is promotion: a freeze window, a copy, a
 *       verified row count and a cache eviction (ADR 0010 §6, Phase 5). It is not something a signup
 *       retry or a dev re-adopt may do as a side effect of a config flag.</li>
 * </ul>
 */
@Component
public class TenantPlacements {

    private static final String COLUMNS =
            "org_id, schema_name, datasource_name, state, schema_version, last_error, updated_at";

    /**
     * Enough of the failure to act on and not enough to bloat a row that a fleet-wide query selects.
     * A stack trace belongs in the log the same failure writes; what belongs HERE is the sentence that
     * tells whoever runs the query whether this is one tenant's problem or the whole fleet's.
     */
    private static final int MAX_ERROR = 2000;

    private final JdbcTemplate jdbc;

    TenantPlacements(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The tenant's recorded home, or empty for a tenant this registry has never heard of. */
    public Optional<TenantPlacement> find(UUID orgId) {
        return jdbc.query("select " + COLUMNS + " from platform.tenant_placement where org_id = ?",
                        mapper(), orgId)
                .stream().findFirst();
    }

    /**
     * <strong>The announcement gate.</strong> Records this tenant as placed and serving, and returns
     * {@code true} only for the call that actually made that transition — which is the call that must
     * publish {@code OrganizationRegistered}, and the only one.
     *
     * <p><strong>Call it inside the transaction that writes the tenant</strong>, immediately before
     * publishing. Under the pooled policy this row is the ONLY new fact a signup produces (§4.3:
     * "nothing changes" — {@code tenant_pool} already exists), so it joins the same atomic write as the
     * organization, its roles and its first membership, and a crash leaves neither a tenant without a
     * placement nor a placement without a tenant.
     *
     * <p><strong>Why the answer comes from a row count and not from a read.</strong> "Has this tenant
     * been announced?" asked as a SELECT and acted on afterwards is a race with a window exactly as
     * long as the code between them; two workers retrying the same signup would both read "no" and
     * both publish, and {@code OrganizationRegistered} fans out to a trial, a billing account and a
     * search document that are not all idempotent. {@code ON CONFLICT … DO UPDATE … WHERE state <>
     * 'ACTIVE'} makes the question and the claim the same statement: the row is locked for the
     * duration, the loser's update matches nothing, and Postgres reports zero rows affected.
     *
     * <p><strong>A new pooled tenant inherits the pool's recorded version</strong> through the scalar
     * subquery, because it is literally moving into a schema that some other row already describes.
     * Reading it back rather than passing it in keeps the version a property of the SCHEMA with one
     * writer — the migration runner — instead of something a signup path could invent.
     *
     * @return true iff this call transitioned the tenant into {@link PlacementState#ACTIVE}
     */
    public boolean announce(UUID orgId, String schemaName) {
        return jdbc.update("""
                insert into platform.tenant_placement (%s)
                values (?, ?, ?, 'ACTIVE',
                        (select p.schema_version from platform.tenant_placement p
                          where p.schema_name = ? and p.schema_version is not null limit 1),
                        null, now())
                on conflict (org_id) do update
                   set state = 'ACTIVE', last_error = null, updated_at = now()
                 where tenant_placement.state <> 'ACTIVE'
                """.formatted(COLUMNS),
                orgId, schemaName, TenantPlacement.PRIMARY_DATASOURCE, schemaName) == 1;
    }

    /**
     * Claims {@code schemaName} as this tenant's home and marks it {@link PlacementState#PROVISIONING}
     * — written BEFORE any DDL, so a crash mid-provision leaves a row that says so.
     *
     * <p>Refuses an ACTIVE tenant: see the class note. A FAILED or PROVISIONING row is re-claimed and
     * its {@code last_error} cleared, which is what makes a retry a retry rather than a second tenant.
     *
     * @return true iff the tenant now holds a PROVISIONING placement for {@code schemaName}
     */
    public boolean reserve(UUID orgId, String schemaName) {
        return jdbc.update("""
                insert into platform.tenant_placement (%s)
                values (?, ?, ?, 'PROVISIONING', null, null, now())
                on conflict (org_id) do update
                   set schema_name = excluded.schema_name,
                       datasource_name = excluded.datasource_name,
                       state = 'PROVISIONING',
                       last_error = null,
                       updated_at = now()
                 where tenant_placement.state <> 'ACTIVE'
                """.formatted(COLUMNS),
                orgId, schemaName, TenantPlacement.PRIMARY_DATASOURCE) == 1;
    }

    /**
     * Records that {@code schemaName} has been migrated to {@code version} — <strong>keyed by schema,
     * not by tenant</strong>, so one statement moves every tenant that lives there. Five thousand
     * pooled tenants share {@code tenant_pool}; the day the pool moves, this is the whole update.
     *
     * <p>It deliberately does not touch {@code state}. A schema being at head says nothing about
     * whether the tenants in it have been announced, and conflating the two would announce a tenant as
     * a side effect of a migration run.
     *
     * @return how many tenants live in that schema, which is also how many rows moved
     */
    public int recordMigrated(String schemaName, String version) {
        return jdbc.update("""
                update platform.tenant_placement
                   set schema_version = ?, last_error = null, updated_at = now()
                 where schema_name = ?
                """, version, schemaName);
    }

    /**
     * Records that this tenant's provisioning did not finish, and why. <strong>Must run outside the
     * transaction that failed</strong> — a FAILED row written inside it rolls back with it, and the
     * whole point of the state is that it outlives the attempt.
     *
     * <p>Never touches an ACTIVE row. A tenant that is already serving does not become unserveable
     * because a later attempt to move it failed; that failure belongs to the move, and the tenant is
     * exactly as fit as it was before the attempt.
     *
     * <p><strong>The seam with the fleet runner, and it is closed rather than noted.</strong>
     * {@code TenantMigrationRunner} writes {@code state = case when state = 'FAILED' then 'ACTIVE' else
     * state end} after a successful pass, on the assumption that a FAILED it sees is a FAILED it wrote.
     * This method breaks that assumption: a provisioning failure is also a FAILED, and it carries the
     * extra meaning "never announced". A runner pass that rescued one of those would flip it to ACTIVE,
     * {@link #announce} would then decline forever — its {@code ON CONFLICT … WHERE state <> 'ACTIVE'}
     * arm matches nothing — and that tenant would have no trial, no billing account and no search
     * document behind a registry row that reads perfectly healthy.
     *
     * <p><strong>That used to read "it takes a non-default policy to reach", and that sentence is what
     * made nobody look.</strong> The first term became the DEFAULT when {@code silo-per-org} shipped
     * (2026-08-08): every signup now creates a schema and runs a fresh tenant sequence, so the failed
     * provision it needs is one transient lock away on the ordinary path, and the runner pass is the next
     * deploy. The fix is where that note said it belonged — one predicate in the runner's statements,
     * {@code TenantMigrationRunner.NOT_A_PROVISIONING_FAILURE}, and not a fourth state here. The
     * discriminator is this method's own restraint: it never writes {@code schema_version}, and the row
     * it marks was inserted by {@link #reserve} without one, so a FAILED with no version is provisioning's
     * and the runner leaves it alone. <strong>Do not start writing a version here</strong> — that is what
     * the runner reads to tell the two failures apart.
     */
    public void markFailed(UUID orgId, String reason) {
        jdbc.update("""
                update platform.tenant_placement
                   set state = 'FAILED', last_error = ?, updated_at = now()
                 where org_id = ? and state <> 'ACTIVE'
                """, truncate(reason), orgId);
    }

    /**
     * The fleet-health query the registry exists for, oldest first — served by
     * {@code idx_tenant_placement_unhealthy} for any state but ACTIVE. "Which tenants are not fit to
     * serve, and since when" answered with a {@code select} rather than by grepping a pod that has
     * since been replaced (ADR 0010 §4.2).
     */
    public List<TenantPlacement> findByState(PlacementState state) {
        return jdbc.query("select " + COLUMNS + " from platform.tenant_placement"
                        + " where state = ? order by updated_at",
                mapper(), state.name());
    }

    /**
     * <strong>The three writes that move a SERVING tenant, and the only ones in this class that may.</strong>
     * ADR 0010 §6 hop 0→1, Phase 5 — {@code shared.tenancy.promotion.TenantPromoter} is the only caller,
     * and the class note above is the reason they are here rather than there: {@link #reserve} declines
     * an ACTIVE row precisely so nothing can move a serving tenant by accident, and the deliberate
     * exception belongs beside the refusal it makes an exception to.
     *
     * <p>All three are compare-and-swap, and every one of them names BOTH the schema it expects to find
     * and the state it expects to find it in. That is what makes them safe to retry and impossible to
     * interleave: a second promoter, a demotion racing a promotion, or a re-run of a step that already
     * succeeded updates nothing and is told so by the row count. Without the {@code schema_name} arm, a
     * re-run of a completed promotion would "move" the tenant from the silo to the silo and report
     * success while something else was moving it back.
     *
     * <p><strong>{@code PROVISIONING} means the same thing here as everywhere else</strong> — this
     * tenant's home is not settled, so nothing may sweep it, write it, or announce anything about it.
     * {@code TenantFanOut} reads exactly that state to decide which homes a background job may visit,
     * which is why the promoter takes it for the whole copy rather than only for the flip.
     *
     * <p><strong>And it is the one state with no deadline, which is why the promoter also writes
     * {@code platform.tenant_freeze}.</strong> A promoter killed mid-copy leaves PROVISIONING behind
     * with nothing able to distinguish "a promotion is running" from "a pod died in the night" — and a
     * fan-out reading it stands the pool down for the whole fleet. The freeze row carries the deadline,
     * the holder and the reason that this column cannot; see V58's header and
     * {@code shared.tenancy.promotion.TenantFreezes}.
     *
     * @return true iff this call took the tenant out of service for a move
     */
    public boolean beginRelocation(UUID orgId, String fromSchema) {
        return jdbc.update("""
                update platform.tenant_placement
                   set state = 'PROVISIONING', last_error = null, updated_at = now()
                 where org_id = ? and schema_name = ? and state = 'ACTIVE'
                """, orgId, fromSchema) == 1;
    }

    /**
     * The flip: one statement moves the tenant to its new schema, records that schema's version and puts
     * it back in service.
     *
     * <p><strong>One statement, and that is the point.</strong> A reader that saw the new
     * {@code schema_name} beside a stale {@code PROVISIONING} would route to a schema it believes is
     * unfit; one that saw ACTIVE beside the old schema would serve rows that are about to be deleted.
     * Neither is expressible if the two columns can only change together.
     *
     * <p>The version is passed in rather than read back, because it is a property of the DESTINATION
     * schema which the caller has just migrated and verified against — re-deriving it here would be a
     * second read that could disagree with the one the copy was checked under.
     *
     * @return true iff the tenant was still where the caller left it and is now moved
     */
    public boolean completeRelocation(UUID orgId, String fromSchema, String toSchema, String version) {
        return jdbc.update("""
                update platform.tenant_placement
                   set schema_name = ?, schema_version = ?, state = 'ACTIVE', last_error = null,
                       updated_at = now()
                 where org_id = ? and schema_name = ? and state = 'PROVISIONING'
                """, toSchema, version, orgId, fromSchema) == 1;
    }

    /**
     * Puts a tenant back in service where it already was, after a move that did not happen.
     *
     * <p>This is the {@code finally} of every promotion, and it must be able to run after any failure —
     * which is why it takes the schema the tenant never left and writes no new home. The reason goes in
     * {@code last_error} on an ACTIVE row, a combination nothing else in this class produces and which
     * reads exactly as it should: <em>serving, and the last attempt to move it did not finish</em>. It
     * is cleared by the next successful migration pass over that schema ({@link #recordMigrated}), so it
     * describes the last event rather than accumulating.
     *
     * @return true iff the tenant was mid-move and is now serving again
     */
    public boolean abandonRelocation(UUID orgId, String schemaName, String reason) {
        return jdbc.update("""
                update platform.tenant_placement
                   set state = 'ACTIVE', last_error = ?, updated_at = now()
                 where org_id = ? and schema_name = ? and state = 'PROVISIONING'
                """, truncate(reason), orgId, schemaName) == 1;
    }

    /**
     * Every tenant that is serving from a schema of its own, oldest schema name first — the silo half
     * of Phase 5's fan-out, and the only half that grows.
     *
     * <p><strong>ACTIVE only, and that is the freeze.</strong> A PROVISIONING row is a tenant whose
     * schema is being built or whose rows are being copied into it; a sweep that visited it would write
     * into a half-made home or race the copy. A FAILED one is a home nothing has proved fit. Neither is
     * a place to purge, escalate or bill from. {@link #schemas()} answers the other question — every
     * schema the fleet occupies, whatever condition it is in — which is what the MIGRATION runner needs
     * and what a sweep must not use.
     *
     * <p><strong>Ordered by schema name because a cursor depends on it.</strong> The fan-out's resumable
     * cursor remembers where a deadline-cut run stopped, as a schema name; an unordered list would make
     * "resume after t_ab…" select a different set of survivors on every run, which is a starvation bug
     * wearing a cursor's clothes. {@code order by 1} over {@code idx_tenant_placement_schema} is free.
     *
     * <p>The predicate is a scan of a table with one row per tenant and no index that fits it — around
     * a millisecond at 5,000 tenants, taken once per job run and once per page of the platform ticket
     * queue. If that ever shows up, the index is {@code (state, schema_name) where schema_name <>
     * 'tenant_pool'}; it is not worth a migration before it does.
     */
    public List<TenantPlacement> activeSilos() {
        return jdbc.query("select " + COLUMNS + " from platform.tenant_placement"
                        + " where state = 'ACTIVE' and schema_name <> ? order by schema_name",
                mapper(), TenantSchemas.TENANT_POOL);
    }

    /**
     * <strong>Every tenant whose home the registry has not settled</strong> — {@link PlacementState#PROVISIONING}
     * or {@link PlacementState#FAILED}, whatever schema the row names, oldest first.
     *
     * <p>{@link #activeSilos()} answers "which homes may a sweep visit"; this answers the question that
     * has to be asked beside it — <strong>"which tenants must a sweep leave alone"</strong>. They are not
     * complements: a tenant missing from {@code activeSilos()} is usually a pooled tenant and is
     * occasionally a tenant whose silo is half-built, being torn down, or one failed migration pass old,
     * and treating those two the same is how a siloed tenant's background work ends up running in
     * {@code tenant_pool}. {@code TenantFanOut.Fleet.homeOf} reads this so the difference is expressible.
     *
     * <p>Served by {@code idx_tenant_placement_unhealthy}, the partial index V57 created for exactly this
     * predicate: on a healthy fleet the correct answer is zero rows and costs an empty index scan.
     */
    public List<TenantPlacement> unsettled() {
        return jdbc.query("select " + COLUMNS + " from platform.tenant_placement"
                + " where state <> 'ACTIVE' order by updated_at", mapper());
    }

    /**
     * The version recorded against a SCHEMA, read through any one of the tenants that live in it — the
     * same scalar subquery {@link #announce} embeds, given a name so a second caller does not have to
     * copy it.
     *
     * <p>It is a property of the schema and not of the tenant (see {@link #recordMigrated}), so which
     * row answers is immaterial and {@code limit 1} is honest rather than sloppy. Null means the schema
     * has no recorded version — which V57's backfill makes the state of the whole fleet for one deploy
     * window, and which {@code TenantSchemaFloor} reads as unknown-so-serve rather than as behind.
     */
    public String versionOf(String schemaName) {
        return jdbc.query("""
                select schema_version from platform.tenant_placement
                 where schema_name = ? and schema_version is not null limit 1
                """, (rs, row) -> rs.getString(1), schemaName)
                .stream().findFirst().orElse(null);
    }

    /**
     * <strong>How many tenants this installation has a home for at all</strong> — every state, every
     * schema, pooled and siloed together.
     *
     * <p>It exists to answer one question that has no other source: <em>is this the shared platform, or
     * is it one tenant's own deployment?</em> ADR 0010 §6's extracted deployment is restored with exactly
     * one row here ({@code BundleScriptWriter.writeThePlacementRow} writes it and nothing else adds
     * another), so a boot check that would be a fleet-wide outage on the platform is, at one, a refusal
     * that costs precisely the tenant it is about. {@code PlanCatalogGuard} is the caller and its javadoc
     * argues the line; this is only the count.
     *
     * <p>Deliberately not {@code activeSilos().size()}: a pooled single-tenant installation is the same
     * deployment shape and would answer zero, and an unsettled tenant is still a tenant this deployment
     * exists for.
     */
    public int tenantCount() {
        Integer tenants = jdbc.queryForObject(
                "select count(*) from platform.tenant_placement", Integer.class);
        return tenants == null ? 0 : tenants;
    }

    /** The distinct schemas the fleet occupies — Phase 5's fan-out is O(this), not O(tenants). */
    public List<String> schemas() {
        return jdbc.queryForList(
                "select distinct schema_name from platform.tenant_placement order by 1", String.class);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_ERROR ? reason : reason.substring(0, MAX_ERROR);
    }

    private static RowMapper<TenantPlacement> mapper() {
        return TenantPlacements::map;
    }

    private static TenantPlacement map(ResultSet rs, int row) throws SQLException {
        return new TenantPlacement(
                rs.getObject("org_id", UUID.class),
                rs.getString("schema_name"),
                rs.getString("datasource_name"),
                PlacementState.of(rs.getString("state")),
                rs.getString("schema_version"),
                rs.getString("last_error"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
