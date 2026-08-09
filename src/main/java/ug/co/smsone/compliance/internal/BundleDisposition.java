package ug.co.smsone.compliance.internal;

/**
 * <strong>What an extraction does with one PLATFORM-tier table.</strong> ADR 0010 §6's eleven-item
 * bundle, seen from the side the tenant schema cannot answer for: the 39 tables in {@code platform}
 * each get exactly one of these, and {@link TenantBundlePlan} refuses to plan a bundle while any table
 * in the catalogue has none.
 *
 * <p><b>Why this is an enum and not a set of booleans.</b> §6 lists eight verbs — seeded, projected,
 * snapshotted, fresh, re-minted, rewritten, rebuilt, drained — and every one of them is a different
 * answer to "does this row exist on the other side, and where did it come from". Collapsing them into
 * "copy / do not copy" loses exactly the distinctions the document spends its length on: a re-minted
 * key and a rewritten identity are both "not copied" and mean opposite things to the operator, and
 * {@link #FRESH_AND_EMPTY} is "not copied" in a way that must be <em>impossible</em> rather than merely
 * unimplemented.
 *
 * <p><b>{@link #carriesRows()} is the structural half of §6 item 6.</b> A disposition that does not
 * carry rows has no read SQL anywhere in this package — {@link TenantBundlePlan.PlatformTable#read()}
 * throws for one, and {@code TenantBundler} never asks. That is the difference between a comment saying
 * "never copy {@code shedlock}" and a bundler that cannot express the copy.
 */
enum BundleDisposition {

    /**
     * The org's own rows, copied into the new deployment's {@code platform} schema — §6 items 2 and 5.
     * Selected by an organization predicate, so a neighbour's row cannot ride along.
     *
     * <p>It is also the verb for the one part of §6 that is not rows at all — item 7's object bytes,
     * which are selected by the organization's own key prefixes for exactly the same reason and by
     * exactly the same argument. {@code TenantBundler} reports that part under this disposition and says
     * in its note that it did not perform the copy.
     */
    SEEDED_FOR_THIS_ORG(true),

    /**
     * The whole catalogue table, copied once — §6 item 4, and the trap the document calls the worst it
     * has. {@code plan}, {@code plan_entitlement}, {@code sla_policy}, {@code translation},
     * {@code setting}, {@code feature_flag}: none of them is org-scoped, all of them are read on
     * ordinary request paths, and every one of them fails <em>silently</em> when absent. See
     * {@link TenantBundlePlan#requireTheCatalogueIsWorthShipping} for the verification and the refusal.
     */
    SNAPSHOT_WHOLE_TABLE(true),

    /**
     * A reduced copy of exactly the humans this tenant references — §6 item 3 and §2.2. Not the row:
     * 463 MB of person graph belongs to no tenant, and two global uniqueness constraints
     * ({@code uq_external_identity_subject_live}, {@code uq_person_contact_verified_live}) make a
     * faithful copy an act of putting two systems in violation of them.
     */
    PROJECTED(true),

    /**
     * <strong>Created empty by the migration set and incapable of being carried.</strong> §6 item 6,
     * widened by the catalogue rather than by this file — see {@link TenantBundlePlan}'s note on the
     * two tables that did not exist when §6 was written.
     *
     * <p>The failures are silent and opposite: a copied {@code shedlock} row gives two deployments the
     * same lock names and the new one runs <em>no jobs at all</em> ({@code @SchedulerLock} skips a
     * same-name relock without logging); a copied {@code event_publication} replays events the platform
     * already delivered — duplicate webhooks, duplicate notifications, duplicate indexing. Neither
     * announces itself, which is why this is a disposition with no read behind it rather than a rule
     * somebody remembers.
     */
    FRESH_AND_EMPTY(false),

    /**
     * The new deployment writes its own rows rather than receiving a copy. {@code tenant_placement} is
     * the one table {@code docs/DATA_MODEL.md} already says this about: "<em>a lifted tenant has exactly
     * one placement, its own</em>". Copying the platform's registry would hand a single-tenant
     * deployment 5,000 placements naming schemas it does not have.
     */
    WRITTEN_FRESH(false),

    /**
     * Derived data: the new deployment rebuilds it from rows it already has — §6 item 10 for
     * {@code search_document}, and the same argument for {@code org_membership_index}, which is a
     * routing projection of {@code membership} (ADR 0010 §2.1: "the index routes, the tenant schema
     * authorizes"). Copying either would import rows that are only correct until the first write.
     */
    REBUILT(false),

    /**
     * §6 item 8. The secret never travels — the platform revokes the org's keys and permanently
     * reserves their prefixes (V59), and the extracted deployment mints its own. A bundle that carried
     * {@code secret_hash} would leave one credential valid against two systems, which is the failure
     * both {@code GdprBundleService} and this class exist to keep on the right side of.
     */
    REMINTED(false),

    /**
     * §6 item 9. {@code external_identity} is rewritten, never copied: a copied row puts two systems in
     * violation of {@code uq_external_identity_subject_live}, which is the constraint that means
     * one-login-one-human platform-wide. Which of the two IdP answers the bundle assumes is recorded in
     * the manifest, because it is the one item in §6 that is a decision rather than a mechanism.
     */
    REWRITTEN(false),

    /**
     * §6 item 11. {@code notification_delivery} is drained by the PLATFORM before the tenant leaves and
     * is not copied — the row is pure transport with no tenant meaning once delivered. Draining is a
     * precondition rather than a step: a bundle taken with the org's queue still holding work would
     * lose that work, silently, because nothing on the far side would ever look for it.
     */
    DRAINED(false),

    /**
     * The platform's, and it stays. The person graph (§2.2), the routing and funnel rows that predate
     * or outlive the tenant, and the PLATFORM half of the seven split tables. Each one is a stated loss
     * rather than an omission — {@link TenantBundlePlan} carries a sentence per table and the manifest
     * prints them, because "what does not travel" is the half of an extraction an operator gets
     * surprised by.
     */
    STAYS_ON_THE_PLATFORM(false);

    private final boolean carriesRows;

    BundleDisposition(boolean carriesRows) {
        this.carriesRows = carriesRows;
    }

    /** Whether a bundle produced under this disposition contains rows of the table at all. */
    boolean carriesRows() {
        return carriesRows;
    }
}
