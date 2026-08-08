package ug.co.smsone.organization.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.WindowIterator;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantContext;

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

    /**
     * <b>Two pinned spans, because this pass genuinely spans both tiers</b> (ADR 0010 §2). The scan
     * over {@code organization} is platform-tier and runs on the pin taken here;
     * {@code roleSeeder.seedSystemRoles} writes {@code org_role} and {@code role_permission}, which are
     * the TENANT's, so it runs on that tenant's own axis inside {@link #reconcileEveryOrg}. One wider
     * pin cannot serve both: a platform axis cannot see {@code org_role} at all, and a tenant axis
     * would page the org table from inside one tenant's schema.
     *
     * <p>Note the ordering constraint that creates, and it is why the {@code runAs} wraps the seed call
     * and nothing wider: the {@link WindowIterator} keeps reading pages BETWEEN seeds, so it must find
     * the platform axis restored each time round the loop. The per-org failure isolation below is
     * per-tenant failure isolation now, unchanged — one tenant whose schema is unreachable must not
     * cost every tenant after it its reconciliation.
     */
    @Override
    public void run(ApplicationArguments args) {
        // Declares the platform axis. An ApplicationRunner is the sharpest case in ADR 0010 §3.4:
        // it runs on the boot thread with no request, no scheduler and no executor behind it, so
        // nothing else in the application would ever pin one for it.
        TenantContext.runAsPlatform(this::reconcileEveryOrg);
    }

    private void reconcileEveryOrg() {
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
                // The tenant's own axis: org_role and role_permission are TENANT-tier, so the seeder's
                // unqualified writes mean "this tenant's schema" and nothing else can say which one.
                // runAs and not a bare set(): it restores the platform axis the iterator above pages on.
                TenantContext.runAs(organization.getId(),
                        () -> roleSeeder.seedSystemRoles(organization.getId()));
            } catch (RuntimeException ex) {
                log.error("System-role catalog reconciliation failed for org {} ({}): {}",
                        organization.getId(), organization.getAlias(), ex.toString());
            }
        }
    }
}
