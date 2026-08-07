package ug.co.smsone.identity.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The people and tokens the four impersonation test classes share, in one place.
 *
 * <p>They were four copies, two of them byte-identical, which is how a fixture and the behaviour it is
 * supposed to set up drift apart: a class that fixes its own copy leaves the others pinning a world that
 * no longer exists.
 *
 * <p>Everything is keyed on {@code person.id} now, and every operator is a REAL person with a real
 * {@code external_identity} link — not a bare token subject. That is forced rather than tidier: the
 * session row holds person ids, and {@code ImpersonationFilter} refuses a caller with no person id
 * before it looks at their tier, so an operator who is only a string can no longer open one.
 *
 * <p>Every subject is UUID-suffixed on purpose. One Postgres container serves the whole suite, so rows
 * outlive the test that wrote them and a fixed subject would let one class's leftovers decide another
 * class's result.
 *
 * <p>Deliberately not shared with {@code shared.security.ImpersonationFilterTest}: that class raw-SQL
 * inserts its rows precisely because it must not compile-depend on {@code identity}.
 */
final class ImpersonationFixtures {

    private ImpersonationFixtures() {
    }

    /** A {@code person} row in the given state, plus a Keycloak link, returned as its person id. */
    static UUID provisionedPerson(PersonRepository persons, ExternalIdentityRepository identities,
            String issuer, ProvisioningStatus status) {
        Person person = Person.invited(PersonName.ofParts(null, null), Instant.now());
        switch (status) {
            case ACTIVE -> person.activate(Instant.now());
            case DISABLED -> person.disable(Instant.now());
            case INVITED -> { // provisioned but not yet seen — impersonable, and deliberately so
            }
        }
        UUID personId = persons.save(person).getId();
        identities.save(ExternalIdentity.link(personId, IdentityProvider.KEYCLOAK, issuer,
                subjectOf(personId), null, Instant.now()));
        return personId;
    }

    /** The Keycloak subject a seeded person authenticates with — a stable rendering of their id. */
    static String subjectOf(UUID personId) {
        return "kc-" + personId;
    }

    static JwtRequestPostProcessor support(UUID personId) {
        return operator(personId, PlatformRole.SUPPORT);
    }

    static JwtRequestPostProcessor admin(UUID personId) {
        return operator(personId, PlatformRole.ADMIN);
    }

    static JwtRequestPostProcessor superadmin(UUID personId) {
        return operator(personId, PlatformRole.SUPERADMIN);
    }

    /**
     * A platform operator: a realm role and no org scoping — the two axes are disjoint. The token's
     * {@code iss} matters as much as its subject: {@code external_identity} is keyed on the pair, and a
     * token from an unknown issuer resolves to no person at all.
     */
    static JwtRequestPostProcessor operator(UUID personId, String role) {
        return jwt().jwt(token -> token.subject(subjectOf(personId))
                        .claim("iss", EdgeSeed.ISSUER)
                        .claim("email", personId + "@smsone.co.ug"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
