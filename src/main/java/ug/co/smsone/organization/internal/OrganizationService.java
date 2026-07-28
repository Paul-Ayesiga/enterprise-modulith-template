package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;

/**
 * Organization lifecycle. Creating an org provisions it in Keycloak, projects it locally, seeds the
 * system roles, and installs the first OWNER (so the org is never left without an administrator — the
 * chicken-and-egg that {@code member:invite} would otherwise create). The Keycloak org creation runs
 * before the local transaction; the owner invite provisions that user and mails temporary credentials.
 */
@Service
class OrganizationService {

    private final OrganizationRepository organizations;
    private final KeycloakOrgAdminGateway keycloakOrg;
    private final RoleSeeder roleSeeder;
    private final MemberService members;

    OrganizationService(OrganizationRepository organizations, KeycloakOrgAdminGateway keycloakOrg,
            RoleSeeder roleSeeder, MemberService members) {
        this.organizations = organizations;
        this.keycloakOrg = keycloakOrg;
        this.roleSeeder = roleSeeder;
        this.members = members;
    }

    Organization create(String alias, String name, String ownerEmail, String ownerFirstName, String ownerLastName) {
        String normalizedAlias = alias == null ? "" : alias.trim().toLowerCase();
        if (organizations.existsByAlias(normalizedAlias)) {
            throw new ConflictException("An organization with alias '" + normalizedAlias + "' already exists.");
        }
        UUID kcOrgId = keycloakOrg.createOrganization(normalizedAlias, name);
        Organization organization = persist(kcOrgId, normalizedAlias, name);
        members.invite(kcOrgId, ownerEmail, ownerFirstName, ownerLastName, "OWNER");
        return organization;
    }

    @Transactional
    Organization persist(UUID kcOrgId, String alias, String name) {
        Organization organization = organizations.save(Organization.register(kcOrgId, alias, name));
        roleSeeder.seedSystemRoles(kcOrgId);
        return organization;
    }

    Organization require(UUID kcOrgId) {
        return organizations.findByKcOrgId(kcOrgId)
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    @Transactional
    Organization rename(UUID kcOrgId, String name) {
        Organization organization = require(kcOrgId);
        organization.rename(name);
        return organizations.save(organization);
    }

    /**
     * Idempotent get-or-create for the dev bootstrap: reuses the local projection if present, otherwise
     * re-adopts an existing Keycloak org by alias (or creates one), seeds roles, and ensures the owner
     * membership. Tolerant of a local-DB reset while Keycloak state survives.
     */
    Organization ensureBootstrap(String alias, String name, String ownerEmail,
            String ownerFirstName, String ownerLastName) {
        String normalizedAlias = alias == null ? "" : alias.trim().toLowerCase();
        Organization existing = organizations.findByAlias(normalizedAlias).orElse(null);
        UUID kcOrgId = existing != null
                ? existing.getKcOrgId()
                : keycloakOrg.findOrganizationIdByAlias(normalizedAlias)
                        .orElseGet(() -> keycloakOrg.createOrganization(normalizedAlias, name));
        Organization organization = existing != null ? existing : persist(kcOrgId, normalizedAlias, name);
        members.invite(kcOrgId, ownerEmail, ownerFirstName, ownerLastName, "OWNER"); // idempotent
        return organization;
    }
}
