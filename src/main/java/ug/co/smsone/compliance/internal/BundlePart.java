package ug.co.smsone.compliance.internal;

/**
 * <strong>The eleven things an extracted deployment needs, in ADR 0010 §6's order.</strong> The
 * document's list, as a type, so that "did the bundle produce all of it" is a question with a
 * compile-time answer instead of a checklist somebody reads.
 *
 * <p>The order is §6's and is kept deliberately: a manifest that lists its parts in the document's
 * order can be read side by side with the document, which is what an operator does the first time and
 * a reviewer does every time. {@link #ordinal()} is therefore meaningful here in a way it usually is
 * not — do not reorder without moving §6.
 */
enum BundlePart {

    /**
     * 1 — the tenant schema itself: the 20 tenant-tier tables plus the tenant half of the seven split
     * ones. Produced by {@code TenantExtractionService}, which asks the database for the table list
     * rather than keeping one.
     */
    TENANT_SCHEMA,

    /** 2 — {@code organization} and {@code external_organization}, into the new deployment's own {@code platform}. */
    ROUTING_ROWS,

    /** 3 — one reduced {@code person} row per referenced human, copied and never moved (§2.2). */
    PERSON_PROJECTION,

    /**
     * 4 — {@code plan}, {@code plan_entitlement}, {@code sla_policy}, {@code translation},
     * {@code setting}, {@code feature_flag}. §6's worst trap: absent, it fails at boot with two
     * opposite symptoms and no error.
     */
    CATALOG_SNAPSHOT,

    /** 5 — the {@code impersonation_session} rows naming the org, so its audit rows resolve their reason. */
    IMPERSONATION_SESSIONS,

    /**
     * 6 — the tables that arrive fresh and empty. The only part whose deliverable is an ABSENCE, which
     * is why {@link TenantBundlePlan} makes it structural: nothing in this package can read them.
     */
    FRESH_INFRASTRUCTURE,

    /**
     * 7 — the org's bytes under {@code doc/o/<orgId>/} and {@code exch/o/<orgId>/}. Not produced here:
     * the object store is another owner's, and this bundle records the prefixes and refuses to call
     * itself complete until somebody says the bytes were taken. See {@code TenantBundler.Manifest}.
     */
    OBJECT_STORE,

    /** 8 — the org's API keys revoked and their prefixes permanently reserved; the far side mints its own. */
    API_KEYS,

    /** 9 — the IdP decision, recorded; {@code external_identity} is rewritten, never copied. */
    IDENTITY_PROVIDER,

    /** 10 — {@code search_document} rebuilt from the tenant's own rows, not copied. */
    SEARCH_INDEX,

    /**
     * 11 — {@code notification_delivery} drained by the platform, and the in-flight rows of the two
     * queues that ARE in the dump ({@code webhook_delivery}, {@code exchange_job}) settled before it is
     * taken, or the same delivery runs on both sides.
     */
    QUEUES
}
