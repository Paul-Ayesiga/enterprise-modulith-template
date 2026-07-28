package ug.co.smsone.organization.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup reconciliation of every org's system roles against the current {@link ug.co.smsone.organization.Permission}
 * catalog. The seeder only runs at org-provisioning time, so without this pass an enum addition would
 * never reach pre-existing orgs. Per-org transaction (via the seeder); a drift-free org is a no-op
 * read. Failures are logged and skipped so one bad org cannot block startup.
 */
@Component
class SystemRoleCatalogReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemRoleCatalogReconciler.class);

    private final OrganizationRepository organizations;
    private final RoleSeeder roleSeeder;

    SystemRoleCatalogReconciler(OrganizationRepository organizations, RoleSeeder roleSeeder) {
        this.organizations = organizations;
        this.roleSeeder = roleSeeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        organizations.findAll().forEach(organization -> {
            try {
                roleSeeder.seedSystemRoles(organization.getKcOrgId());
            } catch (RuntimeException ex) {
                log.error("System-role catalog reconciliation failed for org {} ({}): {}",
                        organization.getKcOrgId(), organization.getAlias(), ex.toString());
            }
        });
    }
}
