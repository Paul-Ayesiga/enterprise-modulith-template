package ug.co.smsone.organization.internal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.placement.TenantProvisioner;

/**
 * Writes the local tenant atomically: the {@code organization} row, its link to the provider
 * organization, its seeded system roles, the first OWNER membership and that membership's routing row
 * in {@code platform.org_membership_index} all commit together or not at all. A separate bean so the
 * {@code @Transactional} boundary is applied by the proxy (a self-invoked {@code @Transactional}
 * method would be a no-op). Called only after the provider-side steps succeed, so a mid-flight failure
 * leaves no partial local state.
 *
 * <p>Since ADR 0010 Phase 4 the placement row in {@code platform.tenant_placement} joins that same
 * write, and it is also what decides whether {@code OrganizationRegistered} is published at all — see
 * {@link #announce}. Under a silo policy the organization row is claimed one transaction earlier by
 * {@link #reserve}, so that the schema named after it can be built before the tenant is announced
 * (ADR 0010 §4.3); the pooled policy never splits the write.
 *
 * <h2>Which axis this write runs on (ADR 0010 §2, §3.2)</h2>
 *
 * <p>It touches both tiers, and it still takes ONE pinned span — the TENANT's. Everything platform-tier
 * in here is reached BY NAME ({@code @Table(schema = "platform")} on {@code Organization} and
 * {@code ExternalOrganization}, {@code platform.org_membership_index} spelled out in
 * {@link OrgMembershipIndex}), so it resolves from any axis; {@code org_role}, {@code role_permission}
 * and {@code membership} are bare and resolve only from the tenant's. A platform pin would therefore
 * see three of the five tables and none of the ones this class exists to create. That single span is
 * also what keeps the promise in the first paragraph: a second pin would mean a second connection,
 * which would mean a second transaction, which would mean an organization that can exist without an
 * owner.
 *
 * <p>The pin cannot be taken inside — the axis is chosen when the connection is borrowed, and
 * {@code @Transactional} has already borrowed one — so it is the CALLER's, taken around this call and
 * with {@link #tenantAxisOf} to say which tenant. {@link OrganizationService} is that caller.
 */
@Component
class OrgProjectionWriter {

    /**
     * The axis a tenant that does not exist yet is created on. It names no organization on purpose: a
     * tenant that has never been promoted resolves to the shared {@code tenant_pool}, and a UUID that
     * is in no {@code organization} row can never resolve to anything else — so this is the pooled
     * schema's own axis, expressed with the only vocabulary {@code TenantContext} has (it pins an ORG;
     * there is no "the pool" state). Same constant, same reasoning as {@code MappedSchemaValidator} and
     * {@code WebhookSecretEncryptionMigrator}: one idiom for "the pool", not three.
     *
     * <p><strong>It is the right answer for an organization that does not exist yet, and ONLY because
     * under a silo policy that case never reaches {@link #projectWithOwner}.</strong> This javadoc used
     * to say the schema "must land in {@code tenant_pool} in every world", reasoning that a tenant is
     * born pooled and only ever promoted later. That premise died when {@code silo-per-org} became the
     * default on 2026-08-08: under it a tenant is born in {@code t_<32hex>}, and the reason this constant
     * is still correct is a different one — {@code OrganizationService.homeReadyFor} RESERVES the
     * organization row and builds its schema in an earlier transaction, so by the time
     * {@link #tenantAxisOf} is asked the tenant exists and it answers with the real id. The zero uuid is
     * reached only on the pooled path, where the pool really is the home.
     *
     * <p><strong>Which makes the ordering load-bearing rather than tidy.</strong> A caller that invokes
     * {@link #projectWithOwner} under {@code silo-per-org} without going through {@code homeReadyFor}
     * first gets this constant, writes {@code org_role}, {@code role_permission} and {@code membership}
     * into {@code tenant_pool}, and then announces a placement naming a {@code t_<32hex>} schema that was
     * never created — a tenant whose registry row and whose rows are in different schemas. Nothing
     * throws; the next read on that tenant's own axis fails with {@code relation "org_role" does not
     * exist}, three calls and one transaction away from the mistake. {@code TenantPlacementOnCreateTest}
     * drives this class directly and therefore declares {@code app.tenancy.placement.policy=pooled};
     * {@code SiloTenantProvisioningTest} drives {@code OrganizationService} for the other policy.
     */
    private static final UUID NEW_POOLED_TENANT = new UUID(0L, 0L);

    private final OrganizationRepository organizations;
    private final OrgResolver orgResolver;
    private final RoleSeeder roleSeeder;
    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final OrgMembershipIndex membershipIndex;
    private final TenantPlacements placements;
    private final TenantProvisioner provisioner;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrgProjectionWriter(OrganizationRepository organizations, OrgResolver orgResolver, RoleSeeder roleSeeder,
            RoleRepository roles, MembershipRepository memberships, OrgMembershipIndex membershipIndex,
            TenantPlacements placements, TenantProvisioner provisioner,
            ApplicationEventPublisher events, Clock clock) {
        this.organizations = organizations;
        this.orgResolver = orgResolver;
        this.roleSeeder = roleSeeder;
        this.roles = roles;
        this.memberships = memberships;
        this.membershipIndex = membershipIndex;
        this.placements = placements;
        this.provisioner = provisioner;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The tenant axis {@link #projectWithOwner} has to be pinned to, answered BEFORE the transaction
     * opens because by then it is too late (ADR 0010 §3.2).
     *
     * <p>It asks the same two questions {@code projectWithOwner} asks first, in the same order and for
     * the same reason — the provider LINK is the stronger identity, the alias is the fallback — but as
     * a read, on platform-tier tables only, which is why it needs no axis of its own to answer with.
     * Duplicating the lookup costs one indexed read on a path that already makes two network calls to
     * Keycloak; what it buys is that the write below stays a single atomic transaction instead of being
     * split into a "learn the id" commit and a "fill the tenant in" commit, with a crash between them
     * leaving an organization nobody owns.
     *
     * <p>An org that is neither linked nor aliased is being created right now, and gets
     * {@link #NEW_POOLED_TENANT} — see that constant for why a brand-new tenant's schema is never in
     * doubt.
     */
    UUID tenantAxisOf(String externalOrgId, String alias) {
        return orgResolver.organizationIdOfKeycloakOrg(externalOrgId)
                .flatMap(organizations::findById)
                .or(() -> organizations.findByAlias(alias))
                .map(Organization::getId)
                .orElse(NEW_POOLED_TENANT);
    }

    /**
     * Idempotent get-or-create of the whole local tenant. {@code externalOrgId} is the provider's
     * organization id — opaque here, and stored in exactly one place.
     *
     * <p><b>Call it inside {@code TenantContext.callAs(tenantAxisOf(externalOrgId, alias), …)}</b> — see
     * the class note. The tenant-tier half of this write is addressed bare and has no other way to say
     * which schema it means.
     *
     * <p>The tenant is looked up by its provider LINK first and by alias only as a fallback: the link is
     * the stronger identity (an alias is a slug two systems could disagree about), and on the re-adopt
     * path — a Keycloak org that survived a local-DB reset — the link is what says "this is that same
     * tenant" rather than "something here is called the same thing".
     */
    @Transactional
    Organization projectWithOwner(String externalOrgId, String alias, String name, UUID ownerPersonId) {
        Organization organization = getOrCreate(externalOrgId, alias, name);
        orgResolver.linkKeycloakOrg(organization.getId(), externalOrgId, alias);
        UUID orgId = organization.getId();
        roleSeeder.seedSystemRoles(orgId); // idempotent; joins this transaction
        Role ownerRole = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow();
        Membership owner = memberships.findByOrgIdAndPersonId(orgId, ownerPersonId)
                .orElseGet(() -> memberships.save(
                        Membership.create(orgId, ownerPersonId, ownerRole.getId(), Role.OWNER_CODE)));
        // The routing row joins the same atomic write (ADR 0010 §2.1). This is the sharpest place for
        // it: the owner's is the FIRST membership of a brand-new tenant, so a routing row that landed
        // outside this transaction and lost a crash race would leave the founder of an organization
        // unable to see it in their own switcher — with a token that names no org, because they belong
        // to more than one, and therefore no other way in. Unconditional, like saveMembership's, so a
        // re-adopted tenant (a Keycloak org that survived a local reset) also gets its row.
        membershipIndex.record(orgId, ownerPersonId, owner.getStatus());
        announce(orgId, alias);
        return organization;
    }

    /**
     * Claims a home for a tenant that is about to be created, and nothing else: the
     * {@code organization} row and its provider link, both platform-tier, both reached by name — so
     * this runs on ANY axis and takes no tenant pin.
     *
     * <p><strong>It exists for exactly one reason and it is an ordering one</strong> (ADR 0010 §4.3).
     * Under a silo policy the schema is NAMED after the tenant, so the tenant's id has to exist before
     * the schema can be built — and the schema has to be built before {@link #projectWithOwner} runs,
     * because that is the write that announces the tenant. This is the "learn the id" half, its own
     * transaction, publishing nothing.
     *
     * <p>The window it opens is real and is the price of the silo policy: a crash between this commit
     * and {@code projectWithOwner}'s leaves an organization with no owner. It is bounded and it is
     * <em>visible</em> — {@code platform.tenant_placement} says PROVISIONING for that org, which is a
     * queryable fact rather than a row nobody can tell apart from a healthy one — and every step of
     * the sequence is get-or-create, so the retry heals it. The pooled policy never opens the window
     * at all; there, {@code projectWithOwner} is still the single atomic transaction it always was.
     */
    @Transactional
    Organization reserve(String externalOrgId, String alias, String name) {
        Organization organization = getOrCreate(externalOrgId, alias, name);
        orgResolver.linkKeycloakOrg(organization.getId(), externalOrgId, alias);
        return organization;
    }

    private Organization getOrCreate(String externalOrgId, String alias, String name) {
        return orgResolver.organizationIdOfKeycloakOrg(externalOrgId)
                .flatMap(organizations::findById)
                .or(() -> organizations.findByAlias(alias))
                .orElseGet(() -> organizations.save(Organization.register(alias, name)));
    }

    /**
     * Publishes {@code OrganizationRegistered} — once per tenant, ever — and lets the placement
     * registry be the thing that decides whether this is that once (ADR 0010 §4.3).
     *
     * <p><strong>The question used to be "did Hibernate just insert the row?"</strong> and it could no
     * longer be asked: under a silo policy the row is inserted by {@link #reserve}, a transaction and a
     * schema build earlier, so by the time the tenant is ready to be announced nothing in this method
     * remembers that it was new. Worse, it is not a question that survives a crash — a retry that finds
     * the row already there would decline to announce a tenant nobody ever announced.
     *
     * <p>{@link TenantPlacements#announce} answers a durable question instead: it transitions the
     * placement into ACTIVE and reports whether THIS call is the one that made the transition. A
     * re-adopt of an already-serving tenant gets false and publishes nothing; a retry of a tenant that
     * was reserved but never announced gets true, which is the correct and previously impossible
     * answer. Two concurrent creates cannot both get true — the row is locked for the duration of the
     * one statement, so the loser sees ACTIVE and stands down. That matters because this event fans
     * out to a trial, a billing account and a search document.
     *
     * <p>Inside this transaction, deliberately: under the pooled policy the placement row is the ONLY
     * new fact a signup produces, so it commits with the organization, its roles and its first
     * membership or not at all. {@code @ApplicationModuleListener} is an after-commit listener either
     * way, so listeners cannot tell that publication moved.
     */
    private void announce(UUID orgId, String alias) {
        if (placements.announce(orgId, provisioner.homeFor(orgId))) {
            events.publishEvent(new OrganizationRegistered(orgId, alias, clock.instant()));
        }
    }
}
