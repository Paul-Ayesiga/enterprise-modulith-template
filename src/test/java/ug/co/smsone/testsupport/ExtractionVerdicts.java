package ug.co.smsone.testsupport;

import java.util.Map;
import java.util.Set;

/**
 * <strong>What an extraction does with the rows of every PLATFORM-tier table that can name an
 * organization — recorded ONCE, where both of the tests that care can read it.</strong>
 *
 * <h2>Why this file exists, which is a bug it is the fix for</h2>
 *
 * <p>It used to be two files. {@code TenantBundlePlan} (main, {@code compliance.internal}) judged every
 * platform table, and {@code TenantSchemaSelfContainmentTest} (test, {@code shared.tenancy.promotion})
 * kept a second map of its own for the org-scoped subset. They shipped disagreeing on two tables —
 * {@code legal_hold} and {@code api_usage_daily} — and nothing failed, because the self-containment
 * test hand-carries its platform half with its own SQL and never invokes the bundler. One artifact
 * refused to bundle an organization under a live hold; the other asserted end to end, as a shipped
 * property, that holds travel. Both were green. ADR 0010 §7 Phase 6 records the whole episode.
 *
 * <p>The judgement now lives in {@code TenantBundlePlan} and only there. This class is the part of it
 * the promotion-side test needs, spelled in the SAME VOCABULARY — every {@link Verdict} constant is
 * named for a {@code BundleDisposition} constant — and
 * {@code compliance.internal.ExtractionVerdictAgreementTest} fails the build if the two ever drift
 * again, in either direction, including if one grows a constant the other does not have.
 *
 * <p><b>Why a copy at all rather than a call.</b> {@code BundleDisposition} and
 * {@code TenantBundlePlan} are package-private in {@code compliance.internal}; a test in
 * {@code shared.tenancy.promotion} cannot name either of them, and widening them to public would put a
 * module's internals on the compile path of another module's test to save a mirror that a test already
 * checks. The mirror is checked, so it cannot lie; the alternative was checked by nobody, which is how
 * this started.
 */
public final class ExtractionVerdicts {

    /**
     * {@code BundleDisposition}, mirrored by name. Every constant here must exist there and vice versa —
     * asserted, not assumed.
     */
    public enum Verdict {
        /** The org's own rows, copied into the new deployment's {@code platform} schema. */
        SEEDED_FOR_THIS_ORG,
        /** The whole catalogue table, copied once — §6 item 4. */
        SNAPSHOT_WHOLE_TABLE,
        /** A reduced copy of exactly the humans this tenant references — §6 item 3, §2.2. */
        PROJECTED,
        /** Infrastructure: present and EMPTY on the far side. Copying one is its own outage. */
        FRESH_AND_EMPTY,
        /** No rows carried, but a row the DESTINATION must write before it can serve. */
        WRITTEN_FRESH,
        /** Derived data: regenerated on the far side from the tenant's own rows, never copied. */
        REBUILT,
        /** A credential: revoked here, minted fresh there. Nothing crosses. */
        REMINTED,
        /** Rewritten on the far side; a copy would violate a platform-wide uniqueness constraint. */
        REWRITTEN,
        /** Transient work: finished on this side before the dump, so there is nothing to carry. */
        DRAINED,
        /** The platform's own record of something that is not the tenant's to take. */
        STAYS_ON_THE_PLATFORM
    }

    /**
     * One verdict per platform-tier table carrying an {@code org_id}, keyed by table name.
     *
     * <p>The set is asserted against the live catalogue by
     * {@code TenantSchemaSelfContainmentTest.everyPlatformTierTableThatCanNameAnOrganizationCarriesAnExtractionVerdict},
     * so a new org-scoped platform table cannot arrive without somebody answering for its rows. The
     * VALUES are asserted against {@code TenantBundlePlan} by
     * {@code compliance.internal.ExtractionVerdictAgreementTest}.
     */
    private static final Map<String, Verdict> VERDICTS = Map.ofEntries(
            // §6 item 8: the platform revokes the old keys and permanently reserves their prefixes, so
            // the secret hash never travels either way.
            Map.entry("api_key", Verdict.REMINTED),
            // NOT IN §6's list — V59 is the other half of item 8, and its header answers this outright:
            // "deliberately NOT part of an extraction bundle — a lifted deployment mints its own keys
            // into its own prefix space, and inheriting the platform's reservations would only forbid
            // prefixes that mean nothing there." The org_id on it is the operator's audit trail for
            // which extraction burned a prefix, not a claim the rows belong to that tenant.
            Map.entry("api_key_prefix_reservation", Verdict.STAYS_ON_THE_PLATFORM),
            // NOT IN §6's list, and one of the two this file was created to settle. Billing continuity
            // is a real argument for carrying it and it loses to one column: `exported` is
            // UsageExportJob's half of the double-billing guard, a carried row arrives with it false,
            // and the far side then re-prices days the platform has already invoiced. Whoever needs the
            // history for a particular tenant takes it by hand and records that in the handover.
            Map.entry("api_usage_daily", Verdict.STAYS_ON_THE_PLATFORM),
            // §6 item 5: the tenant's audit rows carry impersonation_id, and without these rows an
            // extracted org cannot resolve the operator's stated reason for any of them.
            Map.entry("impersonation_session", Verdict.SEEDED_FOR_THIS_ORG),
            // NOT IN §6's list, and the other one this file settles. SoftDeletePurgeJob's anti-join
            // names platform.legal_hold, which on the far side is ITS legal_hold — so a hold that stayed
            // behind is a hold that stops holding (V34's header, SoftDeletePurgeJob's javadoc, ADR 0010
            // §5.3). The resolution is NOT to carry the hold: a hold is custody rather than data, so the
            // bundler refuses an organization under a live one outright, and the far side's purge
            // running unheld is exactly why. Asserted, both halves, in the self-containment test.
            Map.entry("legal_hold", Verdict.STAYS_ON_THE_PLATFORM),
            // §6 item 11, and §2: pure transport. Drain the PROCESSING rows before dumping or the same
            // delivery runs on both sides.
            Map.entry("notification_delivery", Verdict.DRAINED),
            // NOT IN §6's list. V55's own header answers it: the index ROUTES and the tenant schema
            // AUTHORIZES, drift is harmless in the direction that matters, and OrgMembershipIndexReconciler
            // rebuilds it from the memberships that arrived in the dump. Same class as search_document.
            Map.entry("org_membership_index", Verdict.REBUILT),
            // NOT IN §6's list, but item 6's rule covers it exactly: a claim signal is a statement about
            // work THIS installation still owes. Carried across, it leases a tenant nobody is serving.
            Map.entry("queue_signal", Verdict.FRESH_AND_EMPTY),
            // §6 item 10: extraction REBUILDS the index from the tenant's own rows rather than copying
            // it — which is also why §2.1 could keep it platform-tier at all.
            Map.entry("search_document", Verdict.REBUILT),
            // §2: the row exists before its tenant does and org_id is set only at completion. A signup is
            // a platform lifecycle event, not a tenant's record of itself.
            Map.entry("signup_request", Verdict.STAYS_ON_THE_PLATFORM),
            // NOT IN §6's list. A freeze is an operational state of THIS installation's copy of the
            // tenant; carried across, the extracted deployment starts life refusing its own writes.
            Map.entry("tenant_freeze", Verdict.FRESH_AND_EMPTY),
            // NOT IN §6's list, but docs/DATA_MODEL.md's Tier line already answers it: "the one table an
            // extracted tenant's new deployment writes fresh rather than receiving a copy of — a lifted
            // tenant has exactly one placement, its own". WRITTEN_FRESH and not FRESH_AND_EMPTY, and the
            // distinction is load-bearing: fresh-and-empty is satisfied by doing nothing, and a missing
            // placement row routes the whole restored tenant to an empty tenant_pool in silence.
            Map.entry("tenant_placement", Verdict.WRITTEN_FRESH));

    private ExtractionVerdicts() {}

    /** The judgement, table by table. */
    public static Map<String, Verdict> all() {
        return VERDICTS;
    }

    /** The tables judged here — compared against the live catalogue by the self-containment test. */
    public static Set<String> tables() {
        return VERDICTS.keySet();
    }

    /** What an extraction does with one table's org-scoped rows, or null if it is not judged here. */
    public static Verdict of(String table) {
        return VERDICTS.get(table);
    }
}
