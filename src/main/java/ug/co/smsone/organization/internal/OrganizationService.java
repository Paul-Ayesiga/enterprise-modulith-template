package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.shared.audit.AuditLog;
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
    private final AuditLog auditLog;

    OrganizationService(OrganizationRepository organizations, KeycloakOrgAdminGateway keycloakOrg,
            UserProvisioning userProvisioning, OrgProjectionWriter projectionWriter, AuditLog auditLog) {
        this.organizations = organizations;
        this.keycloakOrg = keycloakOrg;
        this.userProvisioning = userProvisioning;
        this.projectionWriter = projectionWriter;
        this.auditLog = auditLog;
    }

    /**
     * Strict create: any pre-existing org with this alias — local projection OR Keycloak-side — is a
     * 409, never a silent adoption. Adoption would let the loser of a concurrent duplicate-alias race
     * attach ITS owner to the winner's org (an unintended second OWNER). The race on the Keycloak
     * create itself is closed by Keycloak's unique-alias 409, which the gateway maps to a Conflict.
     * Healing an org that survived a local-DB reset goes through {@link #ensureBootstrap} (dev) or ops.
     */
    Organization create(String alias, String name, String ownerEmail, String ownerFirstName, String ownerLastName) {
        String normalizedAlias = normalize(alias);
        if (organizations.existsByAlias(normalizedAlias)
                || keycloakOrg.findOrganizationIdByAlias(normalizedAlias).isPresent()) {
            throw new ConflictException("An organization with alias '" + normalizedAlias + "' already exists.");
        }
        UUID kcOrgId = keycloakOrg.createOrganization(normalizedAlias, name); // concurrent duplicate -> 409
        Organization organization = provisionOwner(kcOrgId, normalizedAlias, name,
                ownerEmail, ownerFirstName, ownerLastName);
        auditLog.record("organization.created", kcOrgId, normalizedAlias, null, "owner=" + ownerEmail);
        return organization;
    }

    /** Idempotent get-or-create for the dev bootstrap: reuses the local projection if present. */
    Organization ensureBootstrap(String alias, String name, String ownerEmail,
            String ownerFirstName, String ownerLastName) {
        String normalizedAlias = normalize(alias);
        return organizations.findByAlias(normalizedAlias)
                .orElseGet(() -> provision(normalizedAlias, name, ownerEmail, ownerFirstName, ownerLastName));
    }

    /**
     * Get-or-create for the dev bootstrap ONLY: re-adopts a Keycloak org that survived a local-DB
     * reset. The admin-facing {@link #create} must never adopt — see its javadoc.
     */
    private Organization provision(String alias, String name, String ownerEmail,
            String ownerFirstName, String ownerLastName) {
        UUID kcOrgId = keycloakOrg.findOrganizationIdByAlias(alias)
                .orElseGet(() -> keycloakOrg.createOrganization(alias, name));
        return provisionOwner(kcOrgId, alias, name, ownerEmail, ownerFirstName, ownerLastName);
    }

    /** Provision the owner identity, link the Keycloak membership, then one atomic local write. */
    private Organization provisionOwner(UUID kcOrgId, String alias, String name, String ownerEmail,
            String ownerFirstName, String ownerLastName) {
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
        String previousName = organization.getName();
        organization.rename(name);
        Organization saved = organizations.save(organization);
        auditLog.record("organization.renamed", kcOrgId, organization.getAlias(), previousName, name);
        return saved;
    }

    @Transactional
    Organization suspend(UUID kcOrgId) {
        Organization organization = require(kcOrgId);
        String previousStatus = organization.getStatus().name();
        organization.suspend(); // publishes OrganizationStatusChanged -> permission cache eviction
        Organization saved = organizations.save(organization);
        auditLog.record("organization.suspended", kcOrgId, organization.getAlias(),
                previousStatus, organization.getStatus().name());
        return saved;
    }

    @Transactional
    Organization reactivate(UUID kcOrgId) {
        Organization organization = require(kcOrgId);
        String previousStatus = organization.getStatus().name();
        organization.reactivate();
        Organization saved = organizations.save(organization);
        auditLog.record("organization.reactivated", kcOrgId, organization.getAlias(),
                previousStatus, organization.getStatus().name());
        return saved;
    }

    private static String normalize(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase();
    }
}
