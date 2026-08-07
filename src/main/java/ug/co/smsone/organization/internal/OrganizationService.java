package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProviderOrgMembership;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;

/**
 * Organization lifecycle. Creating an org creates the provider-side organization, provisions the first
 * OWNER as a person (identity port: person row, Keycloak account, set-password invite), attaches them
 * to the provider organization, and only then writes the local tenant (org + provider link + seeded
 * roles + OWNER membership) in a single atomic transaction. All provider steps are get-or-create /
 * idempotent, so a mid-flight failure writes no partial local state and a retry heals cleanly
 * (re-adopting an org that survived a local-DB reset) — the org is never left without an owner (the
 * chicken-and-egg that {@code member:invite} alone would create).
 *
 * <p>Every {@code orgId} in this class is {@code organization.id}. The provider's own organization id
 * is a String, opaque, and travels no further than {@link OrgProjectionWriter} and the two ports that
 * need it.
 */
@Service
class OrganizationService {

    private final OrganizationRepository organizations;
    private final KeycloakOrgAdminGateway keycloakOrg;
    private final PersonProvisioning personProvisioning;
    private final ProviderOrgMembership providerOrgMembership;
    private final OrgProjectionWriter projectionWriter;
    private final AuditLog auditLog;
    private final TransactionTemplate transactionTemplate;

    private final org.springframework.context.ApplicationEventPublisher events;

    OrganizationService(OrganizationRepository organizations, KeycloakOrgAdminGateway keycloakOrg,
            PersonProvisioning personProvisioning, ProviderOrgMembership providerOrgMembership,
            OrgProjectionWriter projectionWriter, AuditLog auditLog,
            TransactionTemplate transactionTemplate,
            org.springframework.context.ApplicationEventPublisher events) {
        this.organizations = organizations;
        this.keycloakOrg = keycloakOrg;
        this.personProvisioning = personProvisioning;
        this.providerOrgMembership = providerOrgMembership;
        this.projectionWriter = projectionWriter;
        this.auditLog = auditLog;
        this.transactionTemplate = transactionTemplate;
        this.events = events;
    }

    private static final org.springframework.data.domain.Sort PLATFORM_SORT =
            org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.desc("createdAt"),
                    org.springframework.data.domain.Sort.Order.desc("id"));

    /** The operator's view: every tenant, newest first, optionally narrowed by status. */
    org.springframework.data.domain.Window<Organization> platformList(OrganizationStatus status,
            ug.co.smsone.shared.web.CursorPageRequest page) {
        return organizations.findBy(
                (root, query, cb) -> status == null ? cb.conjunction() : cb.equal(root.get("status"), status),
                query -> query.limit(page.size()).sortBy(PLATFORM_SORT)
                        .scroll(page.scrollPosition(PLATFORM_SORT)));
    }

    /**
     * Tenant deletion, the lifecycle's terminal step: SUSPENDED first, by force — suspension is the
     * reversible "are you sure" that already cut every member's access, so delete never surprises a
     * live tenant. Soft like every aggregate (restorable until the retention purge); memberships
     * and roles stay in place under it so an un-delete restores a working org. The provider
     * organization is kept — see {@link ug.co.smsone.organization.OrganizationDeleted}.
     */
    void delete(UUID orgId) {
        transactionTemplate.executeWithoutResult(tx -> {
            Organization organization = require(orgId);
            if (organization.getStatus() != OrganizationStatus.SUSPENDED) {
                throw new ug.co.smsone.shared.error.ConflictException(
                        "Suspend the organization first — deletion is only allowed from SUSPENDED.");
            }
            organizations.delete(organization); // soft: @SQLDelete stamps deleted_at
            // A delete fires no @DomainEvents — publish explicitly (evicts permission caches,
            // fans out the org.deleted webhook).
            events.publishEvent(new ug.co.smsone.organization.OrganizationDeleted(
                    orgId, java.time.Instant.now()));
            auditLog.record("organization.deleted", orgId, organization.getAlias(),
                    OrganizationStatus.SUSPENDED.name(), null);
        });
    }

    /**
     * Strict create: any pre-existing org with this alias — local OR provider-side — is a 409, never a
     * silent adoption. Adoption would let the loser of a concurrent duplicate-alias race attach ITS
     * owner to the winner's org (an unintended second OWNER). The race on the Keycloak create itself is
     * closed by Keycloak's unique-alias 409, which the gateway maps to a Conflict. Healing an org that
     * survived a local-DB reset goes through {@link #ensureBootstrap} (dev) or ops.
     */
    NewOrganization create(String alias, String name, String ownerEmail, String ownerGivenName,
            String ownerFamilyName) {
        String normalizedAlias = normalize(alias);
        if (organizations.existsByAlias(normalizedAlias)
                || keycloakOrg.findOrganizationIdByAlias(normalizedAlias).isPresent()) {
            throw new ConflictException("An organization with alias '" + normalizedAlias + "' already exists.");
        }
        // concurrent duplicate -> 409
        String externalOrgId = keycloakOrg.createOrganization(normalizedAlias, name);
        ProvisionedPerson owner = personProvisioning.provision(
                new ProvisionRequest(ownerEmail, ownerGivenName, ownerFamilyName));
        providerOrgMembership.attach(owner.personId(), externalOrgId);
        // The tenant and the audit row that explains it commit together (projectWithOwner's
        // @Transactional joins this template): separate commits would let a crash leave an
        // organization no audit accounts for — and the strict-create retry (409) never writes it.
        return transactionTemplate.execute(tx -> {
            Organization organization = projectionWriter.projectWithOwner(
                    externalOrgId, normalizedAlias, name, owner.personId());
            auditLog.record("organization.created", organization.getId(), normalizedAlias, null,
                    "owner=" + ownerEmail);
            return new NewOrganization(organization, owner.personId());
        });
    }

    /**
     * Both halves of what a create produced. The owner's person id is carried out rather than looked up
     * afterwards because this call is the only place that holds it without a search — and a search for
     * "the OWNER of this org" is a different question that happens to have the same answer today.
     */
    record NewOrganization(Organization organization, UUID ownerPersonId) {
    }

    /** Idempotent get-or-create for the dev bootstrap: reuses the local tenant if present. */
    Organization ensureBootstrap(String alias, String name, String ownerEmail,
            String ownerGivenName, String ownerFamilyName) {
        String normalizedAlias = normalize(alias);
        return organizations.findByAlias(normalizedAlias)
                .orElseGet(() -> provision(normalizedAlias, name, ownerEmail, ownerGivenName, ownerFamilyName));
    }

    /**
     * Get-or-create for the dev bootstrap ONLY: re-adopts a Keycloak org that survived a local-DB
     * reset. The admin-facing {@link #create} must never adopt — see its javadoc.
     */
    private Organization provision(String alias, String name, String ownerEmail,
            String ownerGivenName, String ownerFamilyName) {
        String externalOrgId = keycloakOrg.findOrganizationIdByAlias(alias)
                .orElseGet(() -> keycloakOrg.createOrganization(alias, name));
        return provisionOwner(externalOrgId, alias, name, ownerEmail, ownerGivenName, ownerFamilyName);
    }

    /** Provision the owner person, attach them at the provider, then one atomic local write. */
    private Organization provisionOwner(String externalOrgId, String alias, String name, String ownerEmail,
            String ownerGivenName, String ownerFamilyName) {
        ProvisionedPerson owner = personProvisioning.provision(
                new ProvisionRequest(ownerEmail, ownerGivenName, ownerFamilyName));
        providerOrgMembership.attach(owner.personId(), externalOrgId);
        return projectionWriter.projectWithOwner(externalOrgId, alias, name, owner.personId());
    }

    Organization require(UUID orgId) {
        return organizations.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    @Transactional
    Organization rename(UUID orgId, String name) {
        Organization organization = require(orgId);
        String previousName = organization.getName();
        if (previousName.equals(name)) {
            return organization; // idempotent no-op: no x → x audit row (the house discipline, §6)
        }
        organization.rename(name);
        Organization saved = organizations.save(organization);
        auditLog.record("organization.renamed", orgId, organization.getAlias(), previousName, name);
        return saved;
    }

    @Transactional
    Organization suspend(UUID orgId) {
        Organization organization = require(orgId);
        String previousStatus = organization.getStatus().name();
        organization.suspend(); // publishes OrganizationStatusChanged -> permission cache eviction
        if (organization.getStatus().name().equals(previousStatus)) {
            // the entity early-returned (already suspended) — match it: no event was published, so
            // no SUSPENDED → SUSPENDED audit row either
            return organization;
        }
        Organization saved = organizations.save(organization);
        auditLog.record("organization.suspended", orgId, organization.getAlias(),
                previousStatus, organization.getStatus().name());
        return saved;
    }

    @Transactional
    Organization reactivate(UUID orgId) {
        Organization organization = require(orgId);
        String previousStatus = organization.getStatus().name();
        organization.reactivate();
        if (organization.getStatus().name().equals(previousStatus)) {
            return organization; // already active — same rule as suspend()
        }
        Organization saved = organizations.save(organization);
        auditLog.record("organization.reactivated", orgId, organization.getAlias(),
                previousStatus, organization.getStatus().name());
        return saved;
    }

    private static String normalize(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase();
    }
}
