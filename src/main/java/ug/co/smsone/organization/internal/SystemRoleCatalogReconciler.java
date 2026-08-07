package ug.co.smsone.organization.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.WindowIterator;
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
    private static final int SCAN_PAGE = 200;

    private final OrganizationRepository organizations;
    private final RoleSeeder roleSeeder;

    SystemRoleCatalogReconciler(OrganizationRepository organizations, RoleSeeder roleSeeder) {
        this.organizations = organizations;
        this.roleSeeder = roleSeeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Keyset pages, not findAll(): the org table is unbounded in a multi-tenant system, and this
        // runs on every instance start — heap must not scale with the whole tenant base at once.
        Sort scan = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        WindowIterator<Organization> all = WindowIterator.of(position -> organizations.findBy(
                        (root, query, cb) -> cb.conjunction(),
                        q -> q.limit(SCAN_PAGE).sortBy(scan).scroll(position)))
                .startingAt(ScrollPosition.keyset());
        while (all.hasNext()) {
            Organization organization = all.next();
            try {
                roleSeeder.seedSystemRoles(organization.getId());
            } catch (RuntimeException ex) {
                log.error("System-role catalog reconciliation failed for org {} ({}): {}",
                        organization.getId(), organization.getAlias(), ex.toString());
            }
        }
    }
}
