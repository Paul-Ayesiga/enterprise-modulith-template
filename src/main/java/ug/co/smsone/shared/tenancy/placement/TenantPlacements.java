package ug.co.smsone.shared.tenancy.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

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
     * <p><strong>One seam to be aware of, and it is with the fleet runner.</strong>
     * {@code TenantMigrationRunner} writes {@code state = case when state = 'FAILED' then 'ACTIVE' else
     * state end} after a successful pass, on the reasonable assumption that a FAILED it sees is a FAILED
     * it wrote. This method breaks that assumption: a provisioning failure is also a FAILED, and it
     * carries the extra meaning "never announced". A runner pass that rescues one of those flips it to
     * ACTIVE, and {@link #announce} then declines — leaving a tenant with no trial, no billing account
     * and no search document. It takes a non-default policy plus a failed provision plus a runner pass
     * plus a retry to reach, and the fix is one predicate in that runner's statement (rescue only what
     * it can tell it wrote), not a fourth state here.
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
