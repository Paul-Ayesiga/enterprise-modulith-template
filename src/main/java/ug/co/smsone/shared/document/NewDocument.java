package ug.co.smsone.shared.document;

import java.util.UUID;

/**
 * A document to register over an already-stored object. {@code orgId} null = personal;
 * {@code ownerPersonId} is the {@code person.id} of whoever the document belongs to;
 * {@code source} says where it came from ({@code UPLOAD} for the REST surface, {@code EXCHANGE}
 * for platform-produced artifacts).
 *
 * <p>The owner is required and is never null: {@code document.owner_person_id} is NOT NULL (V23), and a
 * document belongs to a human even when a job produced it. A machine caller therefore cannot register
 * one — that refusal belongs at the door rather than three layers down as a constraint violation.
 *
 * <p><b>{@code orgId} and {@code storageKey} must agree about who owns the bytes</b>, and this record
 * is where that is checked — see {@link #requireOwnerNamespacedKey}. It is the one door every
 * {@code document} row goes through, which is what makes the check complete rather than a convention
 * two controllers happen to keep.
 */
public record NewDocument(UUID orgId, UUID ownerPersonId, String storageKey, String name,
        String contentType, long sizeBytes, String source) {

    public NewDocument {
        if (ownerPersonId == null) {
            throw new IllegalArgumentException("NewDocument.ownerPersonId must not be null");
        }
        storageKey = require(storageKey, 300, "storageKey");
        name = require(name, 255, "name");
        contentType = require(contentType, 100, "contentType");
        source = require(source, 20, "source");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("NewDocument.sizeBytes must not be negative");
        }
        requireOwnerNamespacedKey(orgId, storageKey);
    }

    /**
     * An organization's bytes live under {@code o/<orgId>/}; a person's live under a namespace naming
     * the person and must never claim an org's. Enforced here because this record is the one door every
     * {@code document} row goes through — {@code Documents.register} takes nothing else — so a future
     * controller that mints a flat key fails on its first upload rather than at an extraction two years
     * later.
     *
     * <p><b>Why the key has to carry the org when the row already does.</b> ADR 0010 §6 extracts a
     * tenant's objects BY KEY PREFIX (bundle item 7: the org's bytes under {@code doc/o/<orgId>/} and
     * {@code exch/o/<orgId>/}), so a key that does not name its org is a key no extraction can find and
     * no platform can prove it stopped serving. The database stopped being the backstop at Phase 2:
     * {@code uq_document_storage_key} now exists once per schema, so two tenants can hold the same key
     * and nothing in Postgres says so. The prefix is what is left.
     *
     * <p><b>Why the {@code o/} segment and not the bare id.</b> An org id and a person id are both
     * UUIDs, so {@code doc/<uuid>/} says nothing about which of the two the uuid is — and the person
     * namespaces ({@code doc/u/<personId>/}, {@code u/<personId>/}, {@code avatar/p/<personId>/}) are
     * exactly the ones ADR 0010 §2.2 says must NOT travel with a tenant. One letter is the whole
     * difference between "extract this" and "this belongs to a human who has other organizations".
     *
     * <p><b>Why it checks the whole prefix and not just the segment.</b> The extraction lists
     * {@code doc/o/<orgId>/} and {@code exch/o/<orgId>/} — it does not scan the bucket for keys
     * containing {@code /o/<orgId>/}, because a bucket is a flat key space with no such query. So a key
     * like {@code report/o/<orgId>/x} carries the segment, satisfies every "does it name its org"
     * reading, and is still invisible to the bundle. {@link OrgObjectPrefixes} is the single list both
     * this guard and the extraction read, so the failure lands on the author of the fifth minter rather
     * than on the operator running the extraction two years later.
     */
    private static void requireOwnerNamespacedKey(UUID orgId, String storageKey) {
        if (orgId == null) {
            // Personal: the key names the human. Refusing an org segment here is the other half of the
            // rule — a personal row whose key sat under o/<someOrg>/ would be swept up by that org's
            // prefix extraction, which is the leak in the direction nobody looks for.
            if (OrgObjectPrefixes.namesAnOrganization(storageKey)) {
                throw new IllegalArgumentException("NewDocument.storageKey for a PERSONAL document (null orgId)"
                        + " must not sit under an organization's o/<orgId>/ namespace — an extraction takes"
                        + " that prefix whole (ADR 0010 §6). Key: " + storageKey);
            }
            return;
        }
        if (!OrgObjectPrefixes.covers(orgId, storageKey)) {
            throw new IllegalArgumentException("NewDocument.storageKey for org " + orgId + " must start with"
                    + " one of that organization's declared object prefixes " + OrgObjectPrefixes.forOrg(orgId)
                    + " so an extraction can find the bytes by prefix (ADR 0010 §6 item 7) and so two tenants"
                    + " cannot mint the same key now that uq_document_storage_key is per-schema. A new"
                    + " org-scoped namespace is one root added to OrgObjectPrefixes, after which it is"
                    + " extracted and restored like the others. Key: " + storageKey);
        }
    }

    private static String require(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("NewDocument." + field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("NewDocument." + field + " exceeds " + max + " characters");
        }
        return trimmed;
    }
}
