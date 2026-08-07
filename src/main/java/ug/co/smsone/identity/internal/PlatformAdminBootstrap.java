package ug.co.smsone.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Dev-only: creates the local {@code person} for the platform admin so a realm-imported platform-role
 * holder can call {@code /api/v1/admin/**} out of the box — the no-JIT gate needs a provisioned person,
 * and a platform operator should not have to own an organization to get one. Opt-in via
 * {@code app.identity.dev-bootstrap.enabled=true} (set by the Makefile for local runs).
 *
 * <p>It resolves the account from an <em>existing</em> Keycloak user and never creates one — so
 * enabling it somewhere it does not belong cannot mint a superadmin. Idempotent, and best-effort: a
 * Keycloak outage logs a warning rather than failing startup. The person starts {@code INVITED} and the
 * gate flips it {@code ACTIVE} on the first API call, exactly like an admin-provisioned human.
 *
 * <p>It goes through {@link PersonProvisioningService#adopt} rather than writing rows itself: what "a
 * provisioned person" consists of — the person, the contact, the external identity — is one definition,
 * and a bootstrap with its own copy of it is a bootstrap that drifts and then produces an account the
 * real gate refuses.
 */
@Component
@ConditionalOnProperty(name = "app.identity.dev-bootstrap.enabled", havingValue = "true")
@EnableConfigurationProperties(IdentityDevBootstrapProperties.class)
class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final KeycloakUserAdminGateway keycloak;
    private final PersonProvisioningService provisioning;
    private final IdentityDevBootstrapProperties properties;

    PlatformAdminBootstrap(KeycloakUserAdminGateway keycloak, PersonProvisioningService provisioning,
            IdentityDevBootstrapProperties properties) {
        this.keycloak = keycloak;
        this.provisioning = provisioning;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.email();
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            keycloak.findByEmail(email).ifPresentOrElse(
                    account -> log.info("Dev bootstrap: platform admin {} is person {}",
                            email, provisioning.adopt(email, account.id())),
                    () -> log.warn("Dev bootstrap: no Keycloak account for '{}' — platform admin not seeded", email));
        } catch (RuntimeException ex) {
            log.warn("Dev bootstrap skipped for platform admin '{}' — is Keycloak reachable? ({})",
                    email, ex.getMessage());
        }
    }
}
