package ug.co.smsone.organization.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Dev-only: seeds a first organization with an OWNER at startup so org RBAC is exercisable out of the
 * box. Opt-in via {@code app.organization.dev-bootstrap.enabled=true} (docker/dev). Idempotent, and
 * best-effort — a Keycloak outage logs a warning rather than failing application startup.
 */
@Component
@ConditionalOnProperty(name = "app.organization.dev-bootstrap.enabled", havingValue = "true")
@EnableConfigurationProperties(OrgDevBootstrapProperties.class)
class OrgDevBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrgDevBootstrap.class);

    private final OrganizationService organizations;
    private final OrgDevBootstrapProperties properties;

    OrgDevBootstrap(OrganizationService organizations, OrgDevBootstrapProperties properties) {
        this.organizations = organizations;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            organizations.ensureBootstrap(properties.alias(), properties.name(), properties.ownerEmail(),
                    properties.ownerGivenName(), properties.ownerFamilyName());
            log.info("Dev bootstrap: organization '{}' ready with owner {}",
                    properties.alias(), properties.ownerEmail());
        } catch (RuntimeException ex) {
            log.warn("Dev bootstrap skipped for organization '{}' — is Keycloak reachable? ({})",
                    properties.alias(), ex.getMessage());
        }
    }
}
