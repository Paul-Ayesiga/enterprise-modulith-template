package ug.co.smsone.testsupport;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds the rows the EDGE resolves a request through: a {@code person} plus the
 * {@code external_identity} link that turns a token's subject into one, and the
 * {@code external_organization} link that turns its {@code organization} claim into a tenant.
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
                """, organizationId, "local-" + organizationId.toString().substring(0, 8), "Org " + alias);
        jdbc.update("""
                insert into external_organization (id, organization_id, provider, issuer, external_org_id,
                                                   external_alias, linked_at, version, created_at)
                values (?, ?, 'KEYCLOAK', ?, ?, ?, now(), 0, now())
                """, UUID.randomUUID(), organizationId, ISSUER, externalOrgId, alias);
        return organizationId;
    }
}
