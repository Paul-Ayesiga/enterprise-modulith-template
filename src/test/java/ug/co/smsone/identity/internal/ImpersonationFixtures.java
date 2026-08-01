package ug.co.smsone.identity.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.shared.security.PlatformRole;

/**
 * The subjects and tokens the four impersonation test classes share, in one place.
 *
 * <p>They were four copies, two of them byte-identical, which is how a fixture and the behaviour it is
 * supposed to set up drift apart: a class that fixes its own copy leaves the others pinning a world that
 * no longer exists.
 *
 * <p>Every subject is UUID-suffixed on purpose. One Postgres container serves the whole suite, so rows
 * outlive the test that wrote them and a fixed subject would let one class's leftovers decide another
 * class's result.
 *
 * <p>Deliberately not shared with {@code shared.security.ImpersonationFilterTest}: that class raw-SQL
 * inserts its {@code app_user} row precisely because it must not compile-depend on {@code identity}.
 */
final class ImpersonationFixtures {

    private ImpersonationFixtures() {
    }

    /** A platform operator's subject. */
    static String actor() {
        return "op-" + UUID.randomUUID();
    }

    /** An {@code app_user} row in the given state, returned as its subject. */
    static String provisionedUser(UserRepository users, ProvisioningStatus status) {
        String subject = "kc-" + UUID.randomUUID();
        User user = User.invited(subject, subject + "@smsone.co.ug", Instant.now());
        switch (status) {
            case ACTIVE -> user.activate(Instant.now());
            case DISABLED -> user.disable();
            case INVITED -> { // provisioned but not yet seen — impersonable, and deliberately so
            }
        }
        users.save(user);
        return subject;
    }

    static JwtRequestPostProcessor support(String subject) {
        return operator(subject, PlatformRole.SUPPORT);
    }

    static JwtRequestPostProcessor admin(String subject) {
        return operator(subject, PlatformRole.ADMIN);
    }

    static JwtRequestPostProcessor superadmin(String subject) {
        return operator(subject, PlatformRole.SUPERADMIN);
    }

    /** A platform operator: a realm role and no org scoping — the two axes are disjoint. */
    static JwtRequestPostProcessor operator(String subject, String role) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@smsone.co.ug"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
