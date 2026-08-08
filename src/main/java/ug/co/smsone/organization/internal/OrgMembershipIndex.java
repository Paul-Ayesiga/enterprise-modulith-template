package ug.co.smsone.organization.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code platform.org_membership_index} — the routing answer to "which organizations does this person
 * belong to?", kept in the PLATFORM schema because {@code membership} is not (ADR 0010 §2.1).
 *
 * <p><b>Why it has to exist.</b> Membership is tenant-tier: a tenant that cannot answer "who is in
 * this org, with what role" out of its own schema is not extractable, which is the whole point of the
 * split. Every read of it is single-tenant — {@code OrgAuthorization.permissions(personId, orgId)}
 * already knows the org, because the token's {@code organization} claim resolved it three steps
 * earlier — with exactly ONE exception: the person-first read. {@code GET /me/organizations} asks a
 * question no tenant schema can answer, because the answer spans tenants and the caller has not
 * chosen one yet. Fanned out, that is a query per tenant schema on a route a multi-org human hits on
 * every sign-in; through this table it is one indexed lookup on a connection that is already on the
 * platform axis. It is also why that endpoint survives the split at all.
 *
 * <p><b>The invariant, verbatim from the ADR: the index ROUTES, the tenant schema AUTHORIZES.</b> That
 * is why the row is three routing keys and nothing else — no {@code role_id}, no {@code version}, no
 * {@code created_by}. It is never an authorization source and no permission decision may read it. The
 * shape enforces the rule: there is nothing here to authorize <em>with</em>.
 *
 * <p><b>What happens if the two ever disagree — and they can.</b> Today they cannot drift on any path
 * that goes through this class, because {@link #record} and {@link #forget} run inside the caller's
 * transaction, on the same connection, and commit or roll back with the {@code membership} row itself.
 * They drift on the paths that do not: {@code SoftDeletePurgeJob} hard-deletes aged-out memberships in
 * raw SQL, {@code SoftDeleteRecovery.restore} brings one back, and a test fixture writes the row
 * directly. So the failure modes are worth naming out loud:
 *
 * <ul>
 *   <li><b>Index row with no membership</b> — the person sees an organization in their switcher that
 *       they are no longer in. Choosing it gets them a token scoped to that org, and then
 *       {@code OrgAuthorization} finds no membership and denies everything, because the authoritative
 *       check runs on arrival and reads the tenant, never this table. Ugly, not dangerous. <b>This is
 *       the direction that must stay harmless, and the reason for the invariant above.</b></li>
 *   <li><b>Membership with no index row</b> — the person cannot see an organization they really are
 *       in, and there is no other route to it. Harmless to everyone else and the whole world to them.
 *       This is the one that needs fixing, and it is what the reconciler mostly does.</li>
 * </ul>
 *
 * <p>After a database split (ADR 0010 §7 Phase 7) the shared transaction is gone and the index becomes
 * eventually consistent by construction. That is correct rather than tolerated, for exactly the reason
 * above: a stale index only over- or under-lists a switcher, and the authoritative check still runs on
 * arrival. What makes it safe is that it is never allowed to become anything more than a router.
 *
 * <p><b>There is no foreign key to {@code membership}, and there cannot be.</b> The two tables are in
 * different tiers, and AGENTS §1's second FK clause forbids a constraint across that line — a
 * cross-tier FK is precisely what stops {@code pg_dump -n <schema>} producing a restorable tenant.
 * After Phase 7 they are not even in the same database. {@link OrgMembershipIndexReconciler} is what
 * replaces the constraint, which is the same trade the codebase already made for
 * {@code user_device_trust} when V53 cut its cascade.
 *
 * <p>Raw JDBC rather than an entity, deliberately. An {@code @Entity} would put this projection in the
 * persistence context, where a flush ordering or a cascade could write it without the membership row
 * it mirrors; and it would tempt the next reader to fetch it in a query beside {@code Membership},
 * which is the join that must never exist once the two live in different databases.
 */
@Component
class OrgMembershipIndex {

    /**
     * Always schema-qualified. This class is called from BOTH axes — the invite path runs pinned to
     * the tenant, {@code GET /me/organizations} runs on the platform axis with no tenant at all — and
     * an unqualified name would resolve differently on each. On the tenant path it would simply not
     * exist and the invite would fail loudly; the qualification is what makes one statement mean one
     * thing everywhere (ADR 0010 §3.1).
     *
     * <p><b>This is also what lets the cross-tier write stay ONE pinned span rather than two.</b> The
     * write that matters spans both tiers — {@code membership} is the tenant's, this row is the
     * platform's — and the obvious reading of ADR 0010 §2 ("both → two pinned spans") is the wrong one
     * HERE, for a reason worth stating rather than discovering. Two spans mean two connections, two
     * connections mean two transactions, and two transactions are exactly the state this projection
     * exists to make impossible: a committed membership whose routing row was lost, or a routing row
     * for a membership that rolled back. One tenant span serves both because a qualified name resolves
     * from every axis, so the pair shares one connection and commits or rolls back together. Two spans
     * are the right shape when the two halves genuinely cannot share a connection — see
     * {@code SystemRoleCatalogReconciler}, where a platform-tier scan is INTERLEAVED with per-tenant
     * writes and no single axis can serve both.
     *
     * <p>When Phase 7 puts the tiers in different databases the shared transaction goes and the pair
     * becomes eventually consistent by construction. That is survivable only because of the invariant
     * above — the index routes, the tenant schema authorizes — and because
     * {@link OrgMembershipIndexReconciler} repairs both directions. It is not a reason to give the
     * guarantee up early while it is still free.
     */
    private static final String TABLE = "platform.org_membership_index";

    private final JdbcTemplate jdbc;

    OrgMembershipIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One organization a person belongs to, as the index knows it. Routing keys only — see the class note. */
    record Seat(UUID orgId, String status) {
    }

    /**
     * Records (or refreshes) the routing row for a membership.
     *
     * <p><b>Call it inside the transaction that writes the {@code membership} row</b>, never after it.
     * The two statements then share the connection Spring bound to that transaction and commit
     * together — which is the only reason the pair cannot disagree while they are co-located. Called
     * after the commit instead, a crash in between would leave a member who cannot see their own
     * organization, and nothing would ever notice until they complained.
     *
     * <p>Idempotent by upsert, so it is safe on the re-invite path where the membership already
     * existed — and useful there, because that is what heals a membership created before this index
     * did. The {@code is distinct from} guard means an unchanged row costs no write and produces no
     * dead tuple; on a re-invite storm that is the difference between a no-op and a vacuum problem.
     */
    void record(UUID orgId, UUID personId, MembershipStatus status) {
        jdbc.update("""
                insert into %s as i (person_id, org_id, status) values (?, ?, ?)
                on conflict (person_id, org_id) do update set status = excluded.status
                 where i.status is distinct from excluded.status
                """.formatted(TABLE), personId, orgId, status.name());
    }

    /**
     * Drops the routing row for a membership that is going away. Same transaction rule as
     * {@link #record}: the removal and the row that advertises it must land together, or a removed
     * member keeps an organization in their switcher until the reconciler next runs.
     *
     * <p>A DELETE and not a status flag, because the row means exactly "this person has a live seat
     * here". {@code membership} is soft-deleted so the seat can be restored, and the restore path goes
     * through the reconciler rather than through a tombstone nobody would remember to clear.
     */
    void forget(UUID orgId, UUID personId) {
        jdbc.update("delete from " + TABLE + " where person_id = ? and org_id = ?", personId, orgId);
    }

    /**
     * Every organization this person holds a live seat in, cheapest possible read: one index probe on
     * {@code (person_id, org_id)}, no tenant schema touched, no fan-out. The caller still has to visit
     * each org for anything the tenant owns — its role, for one — and that is the invariant working
     * as designed, not a shortcoming of this method.
     */
    List<Seat> seatsOf(UUID personId, MembershipStatus status) {
        return jdbc.query("select org_id, status from " + TABLE
                        + " where person_id = ? and status = ?",
                (resultSet, row) -> new Seat(resultSet.getObject("org_id", UUID.class),
                        resultSet.getString("status")),
                personId, status.name());
    }
}
