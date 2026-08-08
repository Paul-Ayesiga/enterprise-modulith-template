package ug.co.smsone.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ug.co.smsone.files.FileStorageProvider;

/**
 * <b>The answer to ADR 0010 §8 "Genuinely unknown" item 1: every object key this application mints
 * names its owner, and an organization's says so with an {@code o/<orgId>/} segment.</b> This is the
 * gate that keeps it true.
 *
 * <p><b>Why a key's shape is a correctness property and not a naming convention.</b> ADR 0010 §6
 * extracts a tenant's bytes BY KEY PREFIX — bundle item 7 is "the org's bytes under
 * {@code doc/o/<orgId>/} and {@code exch/o/<orgId>/}". A flat key is therefore a byte no extraction can
 * find, and a personal key that wandered under an org's prefix is a byte an extraction takes that never
 * belonged to the tenant. The database stopped being the backstop at Phase 2: the ADR's own reasoning
 * leaned on {@code uq_document_storage_key} being "globally unique so keys do not collide today", and
 * the split made that index one-per-schema, so two tenants can now hold the same key with nothing in
 * Postgres to say so.
 *
 * <p><b>Two gates, because there are two ways to write bytes.</b> Anything that files a
 * {@code document} row goes through {@link NewDocument}, whose compact constructor refuses a
 * disagreement between {@code orgId} and {@code storageKey} — that covers the org-scoped surface
 * completely, since every org-scoped object in this codebase is filed as a document (the reason
 * {@code ArtifactStore} registers one for every exchange artifact). Anything that writes bytes and
 * files nothing is caught by the enumeration below instead.
 *
 * <p>Its sibling {@code document.internal.DocumentExtractionBoundaryTest} drives the same rule over
 * HTTP against a real container: what this class proves about the shape of a key, that one proves about
 * the keys the live minters actually produce and about the schema the matching row lands in.
 */
class StorageKeyNamespaceTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("ug.co.smsone");

    /**
     * The port methods that CREATE an object. {@code get}, {@code exists}, {@code delete} and
     * {@code presignGet} all take a key that something else already minted, so they are not minting
     * sites and a reader of them cannot get the namespace wrong.
     */
    private static final Set<String> MINTING_METHODS = Set.of("put", "putLarge", "presignPut");

    /**
     * Every class in the application that writes bytes to object storage, and the namespace it mints
     * in. Four, and the split down the middle is the whole point: the first two file a
     * {@code document} row, so {@link NewDocument} checks their keys at runtime on every upload; the
     * last two file nothing, so this table is the only thing that has read their keys.
     *
     * <p><b>This is a decision record, not a coverage list.</b> A fifth writer fails the assertion
     * below and its author has to come here and answer one question — does this belong to an
     * organization or to a human? — which is exactly the question that was never asked when
     * {@code avatar/p/<personId>/} was invented and never written down. Answering it by filing a
     * {@code document} row is the better answer, because then the runtime guard covers the key too and
     * this table is only documentation.
     */
    private static final Map<String, String> MINTERS = minters();

    private static Map<String, String> minters() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("ug.co.smsone.document.internal.OrgDocumentController",
                "doc/o/<orgId>/… for an org's document and doc/u/<personId>/… for a personal one — the "
                        + "namespace is this class's `store` parameter, and both callers file a document");
        declared.put("ug.co.smsone.exchange.internal.ArtifactStore",
                "exch/o/<orgId>/… — job artifacts, filed as EXCHANGE documents");
        declared.put("ug.co.smsone.files.internal.FileController",
                "u/<personId>/… — the raw personal namespace. NO document row: these bytes are addressed "
                        + "by key alone, which is why they are invisible to retention, legal holds and "
                        + "the extraction bundle. Person-scoped, so they never travel with a tenant");
        declared.put("ug.co.smsone.profile.internal.ProfileService",
                "avatar/p/<personId>/… — one object per person, keyed from person_profile.avatar_key. "
                        + "No document row either; person-scoped for the same reason");
        return Map.copyOf(declared);
    }

    /**
     * The org half of the rule, at the one door every {@code document} row goes through. A future
     * controller that mints a flat key fails here — and, because the check is in the record rather than
     * in this test, it fails in that controller's own tests too, on its first upload.
     */
    @Test
    void anOrgScopedDocumentIsRefusedUnlessItsKeyNamesThatOrganization() {
        UUID orgId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .as("a flat key: nothing in it says which tenant's extraction should carry the bytes")
                .isThrownBy(() -> document(orgId, owner, "invoices/2026/07/inv-123.pdf"))
                .withMessageContaining(orgId.toString());
        assertThatIllegalArgumentException()
                .as("the bare id is not enough — an org id and a person id are both UUIDs, so doc/<uuid>/"
                        + " cannot be told apart from the personal namespace it might be")
                .isThrownBy(() -> document(orgId, owner, "doc/" + orgId + "/" + UUID.randomUUID() + "/x.pdf"));
        assertThatIllegalArgumentException()
                .as("another organization's prefix is the collision this rule exists to prevent: after "
                        + "extraction those bytes leave with the wrong tenant")
                .isThrownBy(() -> document(orgId, owner,
                        "doc/o/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/x.pdf"));
        assertThatIllegalArgumentException()
                .as("a prefix match is not a segment match — o/<orgId>abc/ is a different owner")
                .isThrownBy(() -> document(orgId, owner, "doc/o/" + orgId + "extra/" + UUID.randomUUID()));
    }

    /** The two shapes the live org-scoped minters produce, spelled out so a change to either is visible. */
    @Test
    void theLiveOrgScopedMintersProduceKeysThisRuleAccepts() {
        UUID orgId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        assertThatCode(() -> document(orgId, owner,
                "doc/o/" + orgId + "/" + UUID.randomUUID() + "/board_report.pdf"))
                .as("OrgDocumentController.store(file, \"o/\" + orgId, …)")
                .doesNotThrowAnyException();
        assertThatCode(() -> document(orgId, owner,
                "exch/o/" + orgId + "/" + UUID.randomUUID() + "/members-export.csv"))
                .as("ArtifactStore.artifactKey / ExchangeService.submitImport")
                .doesNotThrowAnyException();
    }

    /**
     * The other direction, and the one nobody looks for. A personal document is platform-tier and must
     * NOT travel (ADR 0010 §2.2) — but extraction copies a PREFIX, so a personal row whose key sat
     * under {@code o/<someOrg>/} would be swept up whole by that org's bundle no matter what its
     * {@code org_id} said.
     */
    @Test
    void aPersonalDocumentMayNotClaimAnOrganizationsNamespace() {
        UUID owner = UUID.randomUUID();

        assertThatCode(() -> document(null, owner, "doc/u/" + owner + "/" + UUID.randomUUID() + "/cv.pdf"))
                .as("PersonalDocumentController.upload — the key names the human")
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> document(null, owner,
                        "doc/o/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/cv.pdf"))
                .withMessageContaining("PERSONAL");
    }

    /**
     * Enumerated from the compiled classes, so a new writer shows up here by existing rather than by
     * being remembered. It is the second gate because a writer that files no {@code document} row is
     * invisible to {@link NewDocument} — {@code FileController} and {@code ProfileService} are exactly
     * that today, and both are person-scoped, which is the only reason their absence from the guard is
     * safe.
     */
    @Test
    void everyWriterOfObjectStorageHasDeclaredTheNamespaceItMintsIn() {
        StringBuilder declared = new StringBuilder("a class writes bytes to object storage without having"
                + " declared its key namespace in StorageKeyNamespaceTest.MINTERS. Decide which owner the"
                + " bytes belong to: an organization's go under o/<orgId>/ and should be filed as a"
                + " document (which makes NewDocument check the key for you); a person's go under a"
                + " namespace naming the person and must never sit under an org prefix, because ADR 0010"
                + " §6 extracts a tenant's objects BY PREFIX and takes whatever it finds there."
                + " Declared today:");
        MINTERS.forEach((type, namespace) -> declared.append("\n  ").append(type).append(" → ").append(namespace));

        assertThat(objectStorageWriters())
                .as(declared.toString())
                .containsExactlyInAnyOrderElementsOf(MINTERS.keySet());
    }

    private static Set<String> objectStorageWriters() {
        Set<String> writers = new TreeSet<>();
        for (JavaClass type : PRODUCTION) {
            // The adapter IS the port; its own methods are the implementations, not calls to them. It
            // never sees a key it did not receive, so it cannot mint one.
            if (type.isAssignableTo(FileStorageProvider.class)) {
                continue;
            }
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                if (FileStorageProvider.class.getName().equals(call.getTargetOwner().getFullName())
                        && MINTING_METHODS.contains(call.getTarget().getName())) {
                    writers.add(type.getName());
                }
            }
        }
        // Non-empty is its own assertion: if the importer stopped seeing these classes the enumeration
        // would compare two empty sets and the gate would be gone without a failure anywhere.
        assertThat(writers).as("no object-storage writer was found at all — the gate is not looking at "
                + "the production classes").isNotEmpty();
        return writers;
    }

    /** A registration with everything except the key held constant, so the key is what the test is about. */
    private static NewDocument document(UUID orgId, UUID ownerPersonId, String storageKey) {
        return new NewDocument(orgId, ownerPersonId, storageKey, "file.pdf", "application/pdf", 12, "UPLOAD");
    }
}
