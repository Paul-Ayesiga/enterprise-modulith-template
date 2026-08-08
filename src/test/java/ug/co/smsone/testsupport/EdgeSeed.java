package ug.co.smsone.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Seeds the rows the EDGE resolves a request through: a {@code person} plus the
 * {@code external_identity} link that turns a token's subject into one, and the
 * {@code external_organization} link that turns its {@code organization} claim into a tenant —
 * and, since {@link #multiOrgPerson}, the {@code membership} rows that put one human in several
 * of those tenants at once.
 *
 * <p>It exists because the resolution moved. A test used to authenticate by handing {@code jwt()} any
 * subject string, and everything downstream keyed on that string; now {@code CurrentUserProvider} turns
 * ({@code iss}, {@code sub}) into a {@code person.id} through {@code external_identity} before a
 * controller runs, so a token with no link resolves to no person and holds no org permissions. Seeding
 * that link in thirty test classes by hand is thirty chances to spell the issuer differently — and the
 * issuer is exactly the value that must match byte-for-byte or every caller silently 403s.
 *
 * <p>Raw SQL rather than the repositories on purpose: these tables belong to {@code identity.internal},
 * and a test-support class in another package must not reach into another module's entities. The
 * columns are V10's and the insert is what a provisioning run leaves behind.
 *
 * <p><strong>Every method here writes unqualified tables, so it needs a tenant axis on the calling
 * thread</strong> (ADR 0010 §3.1). {@link TenantAxisExtension} supplies one to every ordinary test, so
 * calls read as they always have. A class carrying {@link NoTenantAxis} must wrap its seeding —
 * {@code TenantContext.callAsPlatform(() -> EdgeSeed.person(jdbc, subject))} — and so must anything
 * seeding from a pooled thread. Every table these methods touch is platform-tier under ADR 0010 §2
 * except {@code org_role} and {@code membership}, which is why the platform axis is enough today and
 * why {@link #member} is the call that will need a tenant axis of its own in Phase 2.
 */
public final class EdgeSeed {

    /**
     * The issuer the resource server is configured with in tests — {@code application.yaml}'s default,
     * since no test profile overrides {@code KEYCLOAK_ISSUER_URI}. It must equal the {@code iss} of the
     * tokens {@code jwt()} mints, which is why {@link #token} is here beside it rather than left to each
     * caller to spell.
     */
    public static final String ISSUER = "http://localhost:8081/realms/smsone";

    private EdgeSeed() {
    }

    /**
     * An active person with a Keycloak link, reachable by {@code subject}. Returns their id.
     *
     * <p>{@code invited_at}, not {@code provisioned_at}: V10 renamed it because "provisioned" named the
     * Keycloak call rather than anything about the human, and the whole point of the decoupling is that
     * a person exists here whether or not any provider has heard of them. {@code activated_at} is set
     * too — a person whose status is ACTIVE but who has no activation instant is a state the real
     * provisioning path never produces.
     */
    public static UUID person(JdbcTemplate jdbc, String subject) {
        UUID personId = UUID.randomUUID();
        jdbc.update("""
                insert into person (id, status, invited_at, activated_at, version, created_at)
                values (?, 'ACTIVE', now(), now(), 0, now())
                """, personId);
        link(jdbc, personId, subject);
        return personId;
    }

    /** The same, plus a verified primary e-mail — for anything that resolves a person BY address. */
    public static UUID personWithEmail(JdbcTemplate jdbc, String subject, String email) {
        UUID personId = person(jdbc, subject);
        jdbc.update("""
                insert into person_contact (id, person_id, kind, contact_value, is_primary, verified_at,
                                            version, created_at)
                values (?, ?, 'EMAIL', ?, true, now(), 0, now())
                """, UUID.randomUUID(), personId, email);
        return personId;
    }

    /**
     * Link an existing person to a Keycloak subject. Separate from {@link #person} because a test that
     * built its person another way still needs the link to be able to authenticate as them.
     */
    public static void link(JdbcTemplate jdbc, UUID personId, String subject) {
        jdbc.update("""
                insert into external_identity (id, person_id, provider, issuer, external_subject,
                                               linked_at, version, created_at)
                values (?, ?, 'KEYCLOAK', ?, ?, now(), 0, now())
                """, UUID.randomUUID(), personId, ISSUER, subject);
    }

    /**
     * The subject to put in a test token for this person. A plain rendering of the person id: it only
     * has to be unique and stable, and using the id makes a failing test's log line traceable to the
     * row without a join.
     */
    public static String subjectFor(UUID personId) {
        return "kc-" + personId;
    }

    /**
     * An {@code organization} row plus the provider link a token's {@code organization} claim resolves
     * through. Returns {@code organization.id} — the tenant key, which the row mints itself.
     *
     * <p>The claim is alias-keyed and carries the PROVIDER's id, so both halves are seeded: the id is
     * what {@code OrgResolver} tries first, the alias is its fallback. Matching the alias against
     * {@code organization.alias} would be the tenant-crossing V11 warns about, so the value seeded here
     * is {@code external_alias} and is deliberately not the local slug.
     */
    public static UUID organization(JdbcTemplate jdbc, String externalOrgId, String alias) {
        UUID organizationId = UUID.randomUUID();
        jdbc.update("""
                insert into organization (id, alias, name, status, version, created_at)
                values (?, ?, ?, 'ACTIVE', 0, now())
                """, organizationId, localAlias(organizationId), "Org " + alias);
        jdbc.update("""
                insert into external_organization (id, organization_id, provider, issuer, external_org_id,
                                                   external_alias, linked_at, version, created_at)
                values (?, ?, 'KEYCLOAK', ?, ?, ?, now(), 0, now())
                """, UUID.randomUUID(), organizationId, ISSUER, externalOrgId, alias);
        return organizationId;
    }

    /**
     * The organization's OWN slug, minted from its id. One place because two callers write and read it
     * — {@link #organization} inserts it and {@link OrgSeat} hands it to assertions — and because the
     * value must stay visibly different from the {@code external_alias} seeded beside it: an assertion
     * that passes with either one would not notice the day a lookup starts matching a token's alias
     * against {@code organization.alias}, which is the tenant crossing V11's header warns about.
     */
    private static String localAlias(UUID organizationId) {
        return "local-" + organizationId.toString().substring(0, 8);
    }

    /**
     * An ACTIVE membership for an existing person in an existing organization, under a role minted
     * here carrying {@code roleCode}. Returns the {@code org_role.id}.
     *
     * <p>It returns the ROLE id rather than the membership id because that is the key callers actually
     * need: {@code org_role} is per-organization by construction, so two organizations never share a
     * role row even when they use the same code, and every batched role lookup on this path
     * ({@code RoleRepository.codeMapByIds}) is keyed by id for exactly that reason.
     *
     * <p>Raw SQL for the reason the class javadoc gives, one module further along: {@code org_role} and
     * {@code membership} belong to {@code organization.internal}. The columns are V11's.
     *
     * <p><strong>It writes the routing row too, and a fixture that forgot to would be lying about the
     * state it claims to seed.</strong> {@code membership} is tenant-tier and
     * {@code platform.org_membership_index} is the platform-side answer to "which organizations is
     * this person in" (ADR 0010 §2.1) — {@code MemberService} and {@code OrgProjectionWriter} write
     * the pair in one transaction, and every real membership in the database therefore has both rows.
     * Seeding only the membership would produce a state the application cannot produce, and
     * {@code GET /me/organizations} would answer an empty list for a person who plainly has a seat.
     * The index is qualified because it is the platform's whichever axis the seeding thread is on.
     */
    public static UUID member(JdbcTemplate jdbc, UUID organizationId, UUID personId, String roleCode) {
        UUID roleId = UUID.randomUUID();
        // The tenant axis this method's javadoc has been promising since Phase 1, declared here rather
        // than left to each caller: org_role and membership are TENANT-tier, so on the harness's
        // PLATFORM pin they would resolve inside `platform` and fail with a relation that plainly
        // exists — and every one of this method's callers wants the same answer, the organization it
        // was already handed. runAs restores whatever the caller had, so nesting inside a
        // callAsPlatform seeding block stays correct.
        TenantContext.runAs(organizationId, () -> {
            jdbc.update("""
                    insert into org_role (id, org_id, code, name, system_role, version, created_at)
                    values (?, ?, ?, ?, false, 0, now())
                    """, roleId, organizationId, roleCode, roleCode);
            jdbc.update("""
                    insert into membership (id, org_id, person_id, role_id, status, version, created_at)
                    values (?, ?, ?, ?, 'ACTIVE', 0, now())
                    """, UUID.randomUUID(), organizationId, personId, roleId);
            // Qualified, so it lands in the platform schema from inside the tenant's axis — which is
            // exactly what OrgMembershipIndex does in production, in the same transaction as the row
            // above. `do nothing` rather than an upsert: a fixture re-seeding the same seat is a test
            // repeating itself, not a status change.
            jdbc.update("""
                    insert into platform.org_membership_index (person_id, org_id, status)
                    values (?, ?, 'ACTIVE')
                    on conflict (person_id, org_id) do nothing
                    """, personId, organizationId);
        });
        return roleId;
    }

    /** One organization a person belongs to, and the role they hold THERE and nowhere else. */
    public record OrgSeat(UUID organizationId, String alias, String roleCode, UUID roleId) {
    }

    /**
     * A person seated in SEVERAL organizations — one seat per {@code roleCodes} entry, in that order —
     * together with the token subject that resolves to them.
     *
     * <p><b>Why this fixture exists.</b> {@code uq_membership_org_person_live} is
     * {@code UNIQUE(org_id, person_id) WHERE deleted_at IS NULL} — per organization, deliberately — so
     * one human in three organizations is legal and always has been. It was also, until this method,
     * hypothetical: the seeded database holds 193,940 people and every one of them belongs to exactly
     * one org, so every code path that fans out over "the organizations the CALLER belongs to" had a
     * green suite proving the one-row case. A person-first read that silently returns the first row, or
     * pays a query per organization, or renders org A's row with org B's role, passes that suite.
     *
     * <p>It is also the case the tenancy split is hardest on (ADR 0010 §2.2): {@code person} is the one
     * identity that cannot live in any tenant's schema precisely because it can belong to many, and
     * what travels on extraction is a projection per organization, not the human. Anything asserted
     * here is asserted about the RESULT — never about how many queries produced it — because the
     * batching that produces it today (ADR 0010 §5 item 4) stops being possible once the role rows sit
     * in separate schemas, and a test written against the query count would then fail for the one
     * reason that is not a regression.
     *
     * <p>Pass DISTINCT role codes unless the test is specifically about repeated ones: identical codes
     * make "the right role in each org" unfalsifiable.
     */
    public record MultiOrgPerson(UUID personId, String subject, List<OrgSeat> seats) {
    }

    /** @see MultiOrgPerson */
    public static MultiOrgPerson multiOrgPerson(JdbcTemplate jdbc, String... roleCodes) {
        if (roleCodes.length < 2) {
            // A one-seat "multi-org" person is the fixture quietly becoming the thing it was written to
            // replace, and every assertion above it would still pass.
            throw new IllegalArgumentException("a multi-org person needs at least two role codes, got "
                    + roleCodes.length);
        }
        String subject = "kc-multi-" + UUID.randomUUID();
        UUID personId = person(jdbc, subject);
        List<OrgSeat> seats = new ArrayList<>(roleCodes.length);
        for (String roleCode : roleCodes) {
            // A fresh organization per seat, each with its own provider link: sharing one would make the
            // fixture depend on which org a token happens to name, and the point here is the person.
            UUID organizationId = organization(jdbc, "kc-org-" + UUID.randomUUID(),
                    "ext-" + UUID.randomUUID());
            seats.add(new OrgSeat(organizationId, localAlias(organizationId), roleCode,
                    member(jdbc, organizationId, personId, roleCode)));
        }
        return new MultiOrgPerson(personId, subject, List.copyOf(seats));
    }
}
