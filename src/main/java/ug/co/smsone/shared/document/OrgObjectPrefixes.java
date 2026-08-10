package ug.co.smsone.shared.document;

import java.util.List;
import java.util.UUID;

/**
 * <b>Where one organization's bytes live in the object store, declared once.</b> ADR 0010 §6 bundle
 * item 7 extracts a tenant's objects <em>by key prefix</em>, so this list is not a naming convention —
 * it is the manifest. A root that is not here is a root no extraction visits.
 *
 * <p><b>The two ways to get this wrong, and both are silent.</b> An org-scoped key minted outside these
 * prefixes is a byte the bundle leaves behind: the tenant arrives on the other side with a
 * {@code document} row whose download 404s, and nothing failed anywhere. A person-scoped key that
 * wandered <em>under</em> one of them is the opposite leak: the extraction copies whatever it finds
 * under the prefix, whatever the row says, so a human's private file departs with an organization that
 * never owned it. {@link NewDocument} refuses both at the one door every {@code document} row goes
 * through; this class is what that door reads.
 *
 * <p><b>Why the roots are named rather than derived.</b> There is no way to ask the object store which
 * of its prefixes belong to organizations — a bucket is a flat key space and {@code doc/} and
 * {@code exch/} mean something only to this application. So the list is hand-kept, which everywhere
 * else in this codebase is the shape that goes stale (the twenty-one-row {@code Extract} list
 * {@code TenantExtractionService} exists to replace). Two things stop it here: adding a root is
 * strictly additive and instantly visible — a new org-scoped minter fails on its first upload with a
 * message naming this class — and {@code StorageKeyNamespaceTest} enumerates every writer of object
 * storage from the compiled classes, so a fifth one cannot appear without someone answering which owner
 * its bytes belong to.
 *
 * <p><b>Adding a root is one line here and nothing else.</b> The extraction reads this list, the
 * registration guard reads this list, and the restore's mirror check reads this list — so a root added
 * here is extracted, refused-when-foreign and restorable the same day.
 *
 * <p><b>A key names an organization and never a deployment, and that is deliberate.</b> ADR 0010 §6 hop
 * 2→3 also requires a fresh object-store root per deployment, and the temptation is to spend a segment
 * of the key on it. There is none to spend: {@code organization.id} does not change on extraction
 * (§2.2), {@code document.storage_key} travels in the tenant's dump verbatim, and the restore's whole
 * correctness argument is that the key it writes back is the key the row already names — which is what
 * makes {@link #covers} true on the far side without anybody rewriting a column. So after a cutover
 * both deployments legitimately hold the same key, and nothing inside the key can ever separate them.
 * The separation lives one level up, in the bucket: {@code files.internal.DeploymentBucket} carries
 * that decision and the purge-after-cutover failure it prevents.
 */
public final class OrgObjectPrefixes {

    /**
     * The first segment of every org-scoped key. {@code doc} is {@code OrgDocumentController}'s uploads;
     * {@code exch} is {@code ArtifactStore}'s job artifacts — sources, results and error reports.
     *
     * <p>The person-scoped roots are deliberately absent and must stay absent: {@code doc/u/<personId>/}
     * (personal documents), {@code u/<personId>/} (raw files, which have no {@code document} row at all)
     * and {@code avatar/p/<personId>/}. A person is platform-tier and global (ADR 0010 §2.2) — when one
     * of their organizations is extracted, the human does not fragment, so their bytes stay.
     */
    private static final List<String> ROOTS = List.of("doc", "exch");

    /**
     * The segment that says "an organization owns what follows". One letter is the whole difference:
     * an org id and a person id are both UUIDs, so {@code doc/<uuid>/} cannot be told apart from the
     * personal namespace it might be.
     */
    private static final String ORG_SEGMENT = "/o/";

    private OrgObjectPrefixes() {
    }

    /**
     * The complete set of key prefixes holding this organization's bytes — the argument list for an
     * object-store extraction, and the thing a restore checks a bundle's keys against.
     *
     * <p>Each one ends with the separator, which is load-bearing rather than tidy: {@code doc/o/<id>}
     * without it is also a prefix of {@code doc/o/<id>extra/…}, so a prefix listing would sweep up an
     * organization whose id merely starts with this one's.
     */
    public static List<String> forOrg(UUID orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("an object prefix is named after an organization; "
                    + "null names nothing");
        }
        return ROOTS.stream().map(root -> root + ORG_SEGMENT + orgId + "/").toList();
    }

    /** Whether {@code storageKey} sits under one of {@code orgId}'s prefixes — the extraction's reach. */
    public static boolean covers(UUID orgId, String storageKey) {
        if (storageKey == null) {
            return false;
        }
        return forOrg(orgId).stream().anyMatch(storageKey::startsWith);
    }

    /**
     * Whether the key claims <em>some</em> organization's namespace, whichever one. The question a
     * personal document has to answer no to: a personal row parked under {@code o/<someOrg>/} leaves
     * with that tenant regardless of its null {@code org_id}, because the bundle copies a prefix whole.
     *
     * <p>Matched anywhere in the path rather than only at a declared root, on purpose. This is the
     * refusing direction, so it should be the wider of the two — a key like {@code report/o/<uuid>/x}
     * is refused as a personal document even though no extraction would visit it, because the honest
     * next step for whoever wrote it is to declare {@code report} as a root, not to file it as a human's.
     */
    public static boolean namesAnOrganization(String storageKey) {
        // Leading separator so the first segment matches the same expression as any later one.
        return storageKey != null && ("/" + storageKey).contains(ORG_SEGMENT);
    }

    /** The declared roots, for a message that has to tell someone what to add and where. */
    public static List<String> roots() {
        return ROOTS;
    }
}
