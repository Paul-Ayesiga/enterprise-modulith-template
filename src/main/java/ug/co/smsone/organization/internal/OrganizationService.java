package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;

/**
 * Organization lifecycle. Creating an org provisions it in Keycloak, provisions the first OWNER
 * (Keycloak user + temporary credentials), links the Keycloak org membership, and only then writes the
 * local projection (org + seeded roles + OWNER membership) in a single atomic transaction. All Keycloak
 * steps are get-or-create / idempotent, so a mid-flight failure writes no partial local state and a
 * retry heals cleanly (re-adopting an org that survived a local-DB reset) — the org is never left
 * without an owner (the chicken-and-egg that {@code member:invite} alone would create).
 */
@Service
class OrganizationService {

    private final OrganizationRepository organizations;
    private final KeycloakOrgAdminGateway keycloakOrg;
    private final UserProvisioning userProvisioning;
    private final OrgProjectionWriter projectionWriter;

    OrganizationService(OrganizationRepository organizations, KeycloakOrgAdminGateway keycloakOrg,
            UserProvisioning userProvisioning, OrgProjectionWriter projectionWriter) {
        this.organizations = organizations;
        this.keycloakOrg = keycloakOrg;
        this.userProvisioning = userProvisioning;
        this.projectionWriter = projectionWriter;
    }

    Organization create(String alias, String name, String ownerEmail, String ownerFirstName, String ownerLastName) {
        String normalizedAlias = normalize(alias);
        if (organizations.existsByAlias(normalizedAlias)) {
            throw new ConflictException("An organization with alias '" + normalizedAlias + "' already exists.");
        }
        return provision(normalizedAlias, name, ownerEmail, ownerFirstName, ownerLastName);
    }

    /** Idempotent get-or-create for the dev bootstrap: reuses the local projection if present. */
    Organization ensureBootstrap(String alias, String name, String ownerEmail,
            String ownerFirstName, String ownerLastName) {
        String normalizedAlias = normalize(alias);
        return organizations.findByAlias(normalizedAlias)
                .orElseGet(() -> provision(normalizedAlias, name, ownerEmail, ownerFirstName, ownerLastName));
    }

    /**
     * Keycloak-first provisioning, then one atomic local write. Reached only when no local projection
     * exists for the alias; each Keycloak call is get-or-create / idempotent so it is safe to retry.
     */
    private Organization provision(String alias, String name, String ownerEmail,
            String ownerFirstName, String ownerLastName) {
        UUID kcOrgId = keycloakOrg.findOrganizationIdByAlias(alias)
                .orElseGet(() -> keycloakOrg.createOrganization(alias, name));
        ProvisionedUser owner = userProvisioning.provision(new ProvisionRequest(ownerEmail, ownerFirstName, ownerLastName));
        keycloakOrg.addMember(kcOrgId, owner.subject());
        return projectionWriter.projectWithOwner(kcOrgId, alias, name, owner.subject());
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

    private static String normalize(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase();
    }
}
