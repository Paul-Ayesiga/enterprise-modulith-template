package ug.co.smsone.identity.internal;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Dev-only: projects the local {@code app_user} row for the platform admin so a realm-imported
 * platform-role holder can call {@code /api/v1/admin/**} out of the box — the no-JIT gate needs a
 * provisioned row, and a platform operator should not have to own an organization to get one. Opt-in via
 * {@code app.identity.dev-bootstrap.enabled=true} (set by the Makefile for local runs).
 *
 * <p>It resolves the subject from an <em>existing</em> Keycloak account and never creates one — so
 * enabling it somewhere it does not belong cannot mint a superadmin. Idempotent, and best-effort: a
 * Keycloak outage logs a warning rather than failing startup. The row starts {@code INVITED} and the
 * gate flips it {@code ACTIVE} on the first API call, exactly like an admin-provisioned user.
 */
@Component
@ConditionalOnProperty(name = "app.identity.dev-bootstrap.enabled", havingValue = "true")
@EnableConfigurationProperties(IdentityDevBootstrapProperties.class)
class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final KeycloakUserAdminGateway keycloak;
    private final UserRepository users;
    private final IdentityDevBootstrapProperties properties;
    private final Clock clock;

    PlatformAdminBootstrap(KeycloakUserAdminGateway keycloak, UserRepository users,
            IdentityDevBootstrapProperties properties, Clock clock) {
        this.keycloak = keycloak;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.email();
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            keycloak.findByEmail(email).ifPresentOrElse(
                    account -> provisionLocally(account.id(), email),
                    () -> log.warn("Dev bootstrap: no Keycloak account for '{}' — platform admin not seeded", email));
        } catch (RuntimeException ex) {
            log.warn("Dev bootstrap skipped for platform admin '{}' — is Keycloak reachable? ({})",
                    email, ex.getMessage());
        }
    }

    private void provisionLocally(String subject, String email) {
        if (users.findBySubject(subject).isPresent()) {
            log.info("Dev bootstrap: platform admin {} already provisioned", email);
            return;
        }
        users.save(User.invited(subject, email, clock.instant()));
        log.info("Dev bootstrap: platform admin {} provisioned (subject {})", email, subject);
    }
}
