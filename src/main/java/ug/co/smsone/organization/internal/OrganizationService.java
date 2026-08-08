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
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.placement.TenantProvisioner;

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
 *
 * <h2>Which methods declare a tenant axis, and which deliberately do not (ADR 0010 §2)</h2>
 *
 * <p>{@link #rename}, {@link #suspend}, {@link #reactivate}, {@link #delete}, {@link #require} and
 * {@link #platformList} touch {@code platform.organization} and nothing else, and {@code AuditLog}
 * spells its own schema out. Every one of them is reached from a platform-scoped route (the operator
 * surface) or an org-scoped one (already pinned by {@code CurrentUserFilter}), and each works from
 * either axis because a qualified name always does. They are left alone.
 *
 * <h2>And where the tenant's schema comes from (ADR 0010 §4.3)</h2>
 *
 * <p>Both provisioning paths go through {@link #homeReadyFor} before they write anything tenant-tier.
 * Under the default pooled policy that is a no-op with a name — {@code tenant_pool} already exists —
 * and the single atomic transaction described above is untouched. Under {@code silo-per-org} it is the
 * step that keeps a tenant from being ANNOUNCED before its schema can serve it, which the three
 * after-commit listeners on {@code OrganizationRegistered} would otherwise lose a race to. Read that
 * method before changing the order of anything in either path.
 *
 * <p>The two provisioning paths are the exception, because they are the only writes in this class that
 * reach the TENANT's own tables — {@code org_role}, {@code role_permission}, {@code membership},
 * through {@link OrgProjectionWriter}. They run from routes that name no tenant ({@code POST
 * /api/v1/orgs} is an operator route; self-service signup has no caller at all), so the axis they
 * arrive on is PLATFORM and the tenant half of that write would find no tables. Both therefore pin
 * explicitly, OUTSIDE the transaction, which is the only place a pin can go.
 */
@Service
class OrganizationService {

    private final OrganizationRepository organizations;
    private final KeycloakOrgAdminGateway keycloakOrg;
    private final PersonProvisioning personProvisioning;
    private final ProviderOrgMembership providerOrgMembership;
    private final OrgProjectionWriter projectionWriter;
    private final TenantProvisioner provisioner;
    private final AuditLog auditLog;
    private final TransactionTemplate transactionTemplate;

    private final org.springframework.context.ApplicationEventPublisher events;

    OrganizationService(OrganizationRepository organizations, KeycloakOrgAdminGateway keycloakOrg,
            PersonProvisioning personProvisioning, ProviderOrgMembership providerOrgMembership,
            OrgProjectionWriter projectionWriter, TenantProvisioner provisioner, AuditLog auditLog,
            TransactionTemplate transactionTemplate,
            org.springframework.context.ApplicationEventPublisher events) {
        this.organizations = organizations;
        this.keycloakOrg = keycloakOrg;
        this.personProvisioning = personProvisioning;
        this.providerOrgMembership = providerOrgMembership;
        this.projectionWriter = projectionWriter;
        this.provisioner = provisioner;
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
        //
        // The pin wraps the template rather than sitting inside it, because the schema is chosen when
        // the connection is borrowed and the template has already borrowed one (ADR 0010 §3.2). The
        // axis is ASKED rather than assumed, and homeReadyFor is where it is asked from: strict create
        // has just proved no org holds this alias, so under the pooled policy the answer is "a new one"
        // — but under a silo policy that method has already reserved the tenant, so the answer is its
        // real id, and one spelling covers the re-adopt path below as well.
        return TenantContext.callAs(homeReadyFor(externalOrgId, normalizedAlias, name),
                () -> transactionTemplate.execute(tx -> {
                    Organization organization = projectionWriter.projectWithOwner(
                            externalOrgId, normalizedAlias, name, owner.personId());
                    auditLog.record("organization.created", organization.getId(), normalizedAlias, null,
                            "owner=" + ownerEmail);
                    return new NewOrganization(organization, owner.personId());
                }));
    }

    /**
     * Makes sure this tenant has somewhere to live BEFORE the write that announces it, and answers with
     * the axis that write must be pinned to (ADR 0010 §4.3).
     *
     * <p><strong>Under the default pooled policy this is one method call and one indexed read.</strong>
     * {@code tenant_pool} already exists, so there is nothing to build, nothing to order, and no second
     * transaction: {@code projectWithOwner} stays the single atomic write it has always been, and the
     * placement row joins it. §4.3 calls this the strongest practical argument for the hybrid, and this
     * method is where the argument is cashed.
     *
     * <p><strong>Under a silo policy the order below is the whole point.</strong> Three
     * {@code @ApplicationModuleListener}s hang off {@code OrganizationRegistered} — the trial, the
     * billing account, the search document — and all three fire after commit, asynchronously, against
     * tenant-tier tables. Build the schema after the announcement and all three race it: every new
     * tenant silently gets no trial and no billing account, and the outbox retries make it look like
     * transient noise. So the schema is built first, and because the schema is NAMED after the tenant,
     * the tenant's row has to be reserved before that. Reserve, build, then announce — never a listener.
     *
     * <p>The two-step is confined to this method rather than pushed into {@link OrgProjectionWriter} so
     * both provisioning paths — the strict admin create and the dev re-adopt — get it from one place,
     * and so the pooled path keeps a call graph with nothing extra in it.
     */
    private UUID homeReadyFor(String externalOrgId, String alias, String name) {
        if (provisioner.schemaPrecedesAnnouncement()) {
            // Platform axis: reserve touches `platform.organization` and `platform.external_organization`
            // and nothing tenant-tier, so it needs no tenant of its own — and could not have one, since
            // naming the tenant is what this step exists to accomplish.
            UUID orgId = TenantContext.callAsPlatform(() -> transactionTemplate.execute(
                    tx -> projectionWriter.reserve(externalOrgId, alias, name).getId()));
            // Outside every transaction, which is why the reserve above had to commit first: this issues
            // DDL, runs Flyway, and on failure writes a FAILED placement row that has to outlive the
            // failure. TenantProvisioner refuses to run inside a transaction for exactly that reason.
            provisioner.provisionHomeFor(orgId);
        }
        return projectionWriter.tenantAxisOf(externalOrgId, alias);
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
        // Same pin as create(), through the same ordering step, and the reason the axis is asked rather
        // than assumed lives here: this is the re-adopt path, so the tenant may ALREADY exist (a
        // Keycloak org that survived a local reset, relinked to a local row this method did not
        // create). Answering with the pool for one of those would write its roles into the wrong schema
        // the day it is promoted — and an already-ACTIVE tenant is precisely the one the provisioner
        // declines to touch, because moving a serving tenant is promotion and promotion has a runbook.
        return TenantContext.callAs(homeReadyFor(externalOrgId, alias, name),
                () -> projectionWriter.projectWithOwner(externalOrgId, alias, name, owner.personId()));
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
