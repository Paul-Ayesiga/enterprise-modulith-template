package ug.co.smsone.organization.internal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.OrganizationRegistered;

/**
 * Writes the local tenant atomically: the {@code organization} row, its link to the provider
 * organization, its seeded system roles, and the first OWNER membership all commit together or not at
 * all. A separate bean so the {@code @Transactional} boundary is applied by the proxy (a self-invoked
 * {@code @Transactional} method would be a no-op). Called only after the provider-side steps succeed,
 * so a mid-flight failure leaves no partial local state.
 */
@Component
class OrgProjectionWriter {

    private final OrganizationRepository organizations;
    private final OrgResolver orgResolver;
    private final RoleSeeder roleSeeder;
    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrgProjectionWriter(OrganizationRepository organizations, OrgResolver orgResolver, RoleSeeder roleSeeder,
            RoleRepository roles, MembershipRepository memberships, ApplicationEventPublisher events,
            Clock clock) {
        this.organizations = organizations;
        this.orgResolver = orgResolver;
        this.roleSeeder = roleSeeder;
        this.roles = roles;
        this.memberships = memberships;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Idempotent get-or-create of the whole local tenant. {@code externalOrgId} is the provider's
     * organization id — opaque here, and stored in exactly one place.
     *
     * <p>The tenant is looked up by its provider LINK first and by alias only as a fallback: the link is
     * the stronger identity (an alias is a slug two systems could disagree about), and on the re-adopt
     * path — a Keycloak org that survived a local-DB reset — the link is what says "this is that same
     * tenant" rather than "something here is called the same thing".
     */
    @Transactional
    Organization projectWithOwner(String externalOrgId, String alias, String name, UUID ownerPersonId) {
        Organization organization = orgResolver.organizationIdOfKeycloakOrg(externalOrgId)
                .flatMap(organizations::findById)
                .or(() -> organizations.findByAlias(alias))
                .orElseGet(() -> register(alias, name));
        orgResolver.linkKeycloakOrg(organization.getId(), externalOrgId, alias);
        UUID orgId = organization.getId();
        roleSeeder.seedSystemRoles(orgId); // idempotent; joins this transaction
        Role ownerRole = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow();
        memberships.findByOrgIdAndPersonId(orgId, ownerPersonId)
                .orElseGet(() -> memberships.save(
                        Membership.create(orgId, ownerPersonId, ownerRole.getId(), Role.OWNER_CODE)));
        return organization;
    }

    /**
     * {@code OrganizationRegistered} is published here rather than registered on the aggregate, because
     * the tenant key it carries does not exist until the row does — Hibernate assigns the id at persist.
     * Publishing inside this transaction is indistinguishable to listeners: {@code @ApplicationModuleListener}
     * is an after-commit listener either way.
     */
    private Organization register(String alias, String name) {
        Organization organization = organizations.save(Organization.register(alias, name));
        events.publishEvent(new OrganizationRegistered(organization.getId(), alias, clock.instant()));
        return organization;
    }
}
