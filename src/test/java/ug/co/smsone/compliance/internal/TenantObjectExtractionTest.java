package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.files.StoredObject;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractObjectStoreIntegrationTest;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>ADR 0010 §6 bundle item 7 against a real object store: one organization's bytes leave, and come
 * back.</b> The sibling of {@code TenantExtractionTest}, which does the same for the tenant's rows —
 * together they are the two halves of a tenant that does not live in the same place.
 *
 * <p>The four claims worth stating before reading any of it, because each one is a different way an
 * extraction can be wrong:
 *
 * <ol>
 *   <li><b>Complete.</b> Every object under the organization's prefixes travels, across page
 *       boundaries, and the tenant's own recorded keys are checked against those prefixes first — so a
 *       key no prefix would find stops the extraction instead of being left behind in silence.</li>
 *   <li><b>Nothing else.</b> A neighbour's objects and all three person-scoped namespaces stay put. A
 *       person is platform-tier and global (§2.2): when one of their organizations leaves, the human
 *       does not fragment.</li>
 *   <li><b>Faithful.</b> Restored objects are byte-identical AND carry their stored content type, which
 *       is what a browser sees on a presigned download and is unrecoverable from the bytes.</li>
 *   <li><b>Refuses in both directions.</b> An extraction taken from the wrong axis, and a bundle
 *       carrying another owner's key, are both stopped rather than tolerated.</li>
 * </ol>
 *
 * <p>Storage is REAL SeaweedFS, never a mocked S3 client (ADR 0003): the two behaviours this class
 * depends on most — {@code isTruncated} on a listing and the content type surviving a round trip — are
 * store behaviour, and a mock would agree with whatever the test expected.
 */
class TenantObjectExtractionTest extends AbstractObjectStoreIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private TenantObjectExtractionService objects;

    @Autowired
    private FileStorageProvider storage;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The whole claim in one test: everything of this organization's, nothing of anybody else's. The
     * neighbour and the three person namespaces are seeded because "carries my objects" and "carries
     * only my objects" are different claims, and only the second one is the boundary.
     */
    @Test
    void everyObjectUnderTheOrganizationsPrefixesTravelsAndNothingElseDoes() {
        UUID orgId = UUID.randomUUID();
        UUID neighbour = UUID.randomUUID();
        UUID person = UUID.randomUUID();

        String mineDoc = put("doc/o/" + orgId + "/" + UUID.randomUUID() + "/board report.pdf",
                "application/pdf", "board");
        String mineExch = put("exch/o/" + orgId + "/" + UUID.randomUUID() + "/members-export.csv",
                "text/csv", "id,name");
        String theirs = put("doc/o/" + neighbour + "/" + UUID.randomUUID() + "/theirs.pdf",
                "application/pdf", "theirs");
        String personalDocument = put("doc/u/" + person + "/" + UUID.randomUUID() + "/payslip.pdf",
                "application/pdf", "payslip");
        String rawFile = put("u/" + person + "/" + UUID.randomUUID() + "/notes.txt", "text/plain", "notes");
        String avatar = put("avatar/p/" + person + "/" + UUID.randomUUID(), "image/png", "avatar");

        Map<String, byte[]> extracted = extract(orgId);

        assertThat(extracted.keySet()).containsExactlyInAnyOrder(mineDoc, mineExch);
        assertThat(extracted.keySet())
                .as("a person is platform-tier and global (ADR 0010 §2.2) — their documents, raw files "
                        + "and avatar stay behind, and so does every other tenant's object")
                .doesNotContain(theirs, personalDocument, rawFile, avatar);
        // The negative asserted on the store as well as on the bundle: an extraction that COPIED them and
        // happened not to report them would look identical here without this.
        assertThat(storage.exists(theirs)).isTrue();
        assertThat(storage.exists(personalDocument)).isTrue();
        assertThat(storage.exists(rawFile)).isTrue();
        assertThat(storage.exists(avatar)).isTrue();
    }

    /**
     * The page boundary, driven at a size a test can build. {@code PAGE_SIZE} is the store's maximum, so
     * the production loop only ever crosses a boundary at a thousand objects — which means the crossing
     * would otherwise be the one part of the extraction that is never executed until a real tenant needs
     * it, and being short by exactly one page is the failure it would produce.
     */
    @Test
    void theListingCrossesPageBoundariesAndReturnsEveryKeyInOrder() {
        UUID orgId = UUID.randomUUID();
        String prefix = "doc/o/" + orgId + "/";
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            expected.add(put(prefix + "u" + i + "/file-" + i + ".pdf", "application/pdf", "body-" + i));
        }

        assertThat(objects.keysUnder(prefix, 3))
                .as("three pages of three, three and one — the last of which is the only short page")
                .containsExactlyElementsOf(expected.stream().sorted().toList());
        assertThat(objects.keysUnder(prefix, 7))
                .as("a page that exactly exhausts the prefix must not be mistaken for a truncated one")
                .hasSize(7);
    }

    /**
     * <b>The durable half of this class.</b> An org-scoped key minted under a root nobody declared is
     * invisible to a prefix listing, so the bundle would be short and nothing would say so — the tenant
     * arrives on the far side with a {@code document} row whose download 404s. The check reads the
     * tenant's own recorded keys and refuses before a byte moves.
     *
     * <p>Written through raw SQL deliberately: {@code NewDocument} refuses this key at registration
     * (that is {@code StorageKeyNamespaceTest}'s subject), so the only way to produce the row is the way
     * a legacy row or a future migration would — around the guard. Both defences are needed and they
     * fail at different times: one when the code is written, this one when the tenant is moved.
     */
    @Test
    void anExtractionRefusesWhenTheTenantHasRecordedAKeyNoPrefixWouldFind() {
        UUID orgId = UUID.randomUUID();
        String undeclared = "report/o/" + orgId + "/" + UUID.randomUUID() + "/q3.pdf";
        insertDocument(orgId, orgId, undeclared);

        assertThatThrownBy(() -> extract(orgId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(undeclared)
                .hasMessageContaining("OrgObjectPrefixes");
    }

    /**
     * The same guard catching the other cause, which is a data bug rather than a naming one: a row with
     * no organization sitting in a tenant's schema. Its key names a PERSON, so no prefix of this tenant
     * covers it — and ADR 0010 §1 calls a row whose org disagrees with its schema the worst failure this
     * design can produce. The extraction is the moment it stops being invisible.
     */
    @Test
    void anExtractionRefusesAPersonalRowFoundInsideTheTenantsSchema() {
        UUID orgId = UUID.randomUUID();
        UUID person = UUID.randomUUID();
        String personalKey = "doc/u/" + person + "/" + UUID.randomUUID() + "/payslip.pdf";
        insertDocument(orgId, null, personalKey);

        assertThatThrownBy(() -> extract(orgId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(personalKey)
                .hasMessageContaining("misroute");
    }

    /**
     * The exchange columns, which are the reason the check does not read {@code document} alone.
     * {@code ArtifactStore} writes the bytes and THEN files the document row, so a crash between the two
     * leaves {@code exchange_job} naming a key no document knows about — and if that key were under an
     * undeclared root, a document-only check would pass.
     */
    @Test
    void theCheckReadsTheExchangeJobKeyColumnsAndNotOnlyTheDocumentTable() {
        UUID orgId = UUID.randomUUID();
        String undeclared = "artifact/o/" + orgId + "/" + UUID.randomUUID() + "/result.csv";
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into exchange_job (id, org_id, requester_person_id, job_type, handler, format,
                                          status, result_key, created_at)
                values (?, ?, ?, 'EXPORT', 'members', 'CSV', 'COMPLETED', ?, now())
                """, UUID.randomUUID(), orgId, UUID.randomUUID(), undeclared));

        assertThatThrownBy(() -> extract(orgId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exchange_job.result_key")
                .hasMessageContaining(undeclared);
    }

    /**
     * The round trip, which is the only thing that makes an extraction a backup rather than a hope. The
     * large object is over the multipart threshold on purpose — {@code write} picks the path, and a
     * restore that single-shot a multi-gigabyte export would fail on the day it mattered.
     *
     * <p>The content type is asserted as loudly as the bytes: downloads are a 302 to a presigned URL, so
     * the object's stored type is what the browser sees and nothing on the application side restates it.
     * A restore that lost it rebuilds the tenant's document store with every PDF downloading as a file.
     */
    @Test
    void theBundleRestoresEveryObjectByteForByteWithItsContentType() throws Exception {
        UUID orgId = UUID.randomUUID();
        byte[] large = new byte[6 * 1024 * 1024]; // over the 5 MiB threshold: write() goes multipart
        new Random(3).nextBytes(large);
        String small = put("doc/o/" + orgId + "/" + UUID.randomUUID() + "/notes.txt", "text/plain", "hello");
        String csv = put("exch/o/" + orgId + "/" + UUID.randomUUID() + "/export.csv", "text/csv", "a,b");
        String big = "doc/o/" + orgId + "/" + UUID.randomUUID() + "/scan.bin";
        storage.put(big, new ByteArrayInputStream(large), large.length, "application/octet-stream");

        Map<String, Copy> bundle = new LinkedHashMap<>();
        TenantContext.runAs(orgId, () -> objects.extract(orgId, object -> bundle.put(object.key(),
                Copy.of(object))));
        assertThat(bundle.keySet()).containsExactlyInAnyOrder(small, csv, big);

        bundle.keySet().forEach(storage::delete);
        assertThat(objects.keysUnder("doc/o/" + orgId + "/", 100)).isEmpty();

        TenantObjectExtractionService.ObjectManifest restored =
                objects.restore(orgId, sink -> bundle.values().forEach(copy -> {
                    // The source closes what the source opened — the sink never owns the stream.
                    try (StoredObject object = copy.open()) {
                        sink.object(object);
                    }
                }));

        assertThat(restored.objects()).isEqualTo(3);
        assertThat(restored.bytes()).isEqualTo(bundle.values().stream().mapToLong(Copy::size).sum());
        for (Copy copy : bundle.values()) {
            try (StoredObject back = storage.open(copy.key())) {
                assertThat(back.contentType()).isEqualTo(copy.contentType());
                assertThat(back.sizeBytes()).isEqualTo(copy.size());
                assertThat(back.content().readAllBytes()).isEqualTo(copy.bytes());
            }
        }
    }

    /**
     * A bundle is a file somebody moved between machines. The one thing a restore must never do is write
     * an object on the word of the bundle it arrived in — so the mirror of the extraction's guard runs
     * per object, and the negative is asserted on the store: the foreign key must not be there
     * <em>afterwards</em>, not merely absent from a count.
     */
    @Test
    void aRestoreRefusesAnObjectThatIsNotThisOrganizationsAndWritesNothing() {
        UUID orgId = UUID.randomUUID();
        UUID neighbour = UUID.randomUUID();
        String foreign = "doc/o/" + neighbour + "/" + UUID.randomUUID() + "/theirs.pdf";
        byte[] payload = "theirs".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> objects.restore(orgId, sink -> sink.object(new StoredObject(
                foreign, "application/pdf", payload.length, new ByteArrayInputStream(payload)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(foreign);

        assertThat(storage.exists(foreign))
                .as("refused means not written — a guard that threw after the put would have leaked the "
                        + "object into this tenant's store anyway")
                .isFalse();
    }

    /**
     * From the platform axis the completeness check would read the PLATFORM copies of the two split
     * tables — every human's personal documents, none of which is under this org's prefix — and report
     * the whole population as offending keys. From another tenant's axis it would check that tenant's
     * keys, pass, and then copy this one's bytes with a guarantee that was never about them: a complete
     * bundle by every appearance, verified against the wrong schema.
     */
    @Test
    void anExtractionRefusesAnyAxisThatIsNotTheTenantsOwn() {
        UUID orgId = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertThatThrownBy(() -> objects.extract(orgId, object -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned to that tenant");
        assertThatThrownBy(() ->
                TenantContext.runAs(other, () -> objects.extract(orgId, object -> { })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned to that tenant");
    }

    /**
     * The same completeness check against a SILOED tenant, and it is falsifiable in the way that matters:
     * the offending row exists only in {@code t_<hex>}, so a check that read {@code tenant_pool} — which
     * is what an unpinned or wrongly-pinned read would do — would find nothing and report a clean
     * extraction. The object prefixes themselves do not change at promotion (they name the organization,
     * not the schema), which is exactly why the ROW side is the half worth proving here.
     */
    @Test
    void theCheckReadsTheTenantsOwnSchemaWhenTheTenantHasBeenPlacedInASilo() {
        UUID orgId = UUID.randomUUID();
        silos.place(orgId);
        String undeclared = "report/o/" + orgId + "/" + UUID.randomUUID() + "/q3.pdf";
        UUID documentId = insertDocument(orgId, orgId, undeclared);
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, orgId, "document", "id", documentId);

        assertThatThrownBy(() -> extract(orgId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(undeclared);
    }

    /**
     * <b>The list that keeps the hand-kept list honest.</b> {@code OBJECT_KEY_COLUMNS} names the tenant
     * columns holding an object-store key, and a hand-kept list is the shape this codebase distrusts —
     * it is exactly the twenty-one-row {@code Extract} list that {@code TenantExtractionService} exists
     * to replace. It cannot be derived (see that field's javadoc: {@code integration_setting.setting_key}
     * is a settings NAME), so instead the schema is enumerated and every {@code _key}-shaped column has
     * to be classified. A new one fails here until its author says which of the two it is.
     */
    @Test
    void everyKeyShapedColumnInTheTenantSchemaIsClassifiedAsAnObjectKeyOrDeliberatelyNot() {
        Set<String> declared = new TreeSet<>();
        // Read off the service rather than restated, so the two cannot drift apart in the one direction
        // that matters: a column removed from the service but still listed here would pass forever.
        objectKeyColumns().forEach(declared::add);
        declared.addAll(NOT_OBJECT_KEYS.keySet());

        Set<String> inTheSchema = new TreeSet<>(TenantContext.callAs(UUID.randomUUID(), () ->
                jdbc.queryForList("""
                        select c.relname || '.' || a.attname
                          from pg_class c
                          join pg_namespace n on n.oid = c.relnamespace
                          join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
                         where n.nspname = current_schema() and c.relkind = 'r'
                           and a.attname like '%\\_key'
                        """, String.class)));

        StringBuilder why = new StringBuilder("a column whose name ends in _key appeared in the tenant "
                + "schema without being classified. If it holds an OBJECT-STORE key, add it to "
                + "TenantObjectExtractionService.OBJECT_KEY_COLUMNS — otherwise an extraction cannot "
                + "check that its bytes are reachable by prefix, and a bundle short by those objects "
                + "says nothing at all. If it does not, add it below with the reason. Not object keys "
                + "today:");
        NOT_OBJECT_KEYS.forEach((column, reason) -> why.append("\n  ").append(column).append(" → ")
                .append(reason));

        assertThat(inTheSchema).as(why.toString()).isSubsetOf(declared);
    }

    /**
     * Tenant columns that end in {@code _key} and hold nothing an object store has ever heard of. A
     * decision record, not an exclusion list: each entry is somebody having read the column and said so.
     */
    private static final Map<String, String> NOT_OBJECT_KEYS = Map.of(
            "integration_setting.setting_key",
            "a settings NAME (V33) — values look like `webhook.url`, and half of them are secrets. "
                    + "Nothing here addresses bytes",
            "feature_flag_org_override.flag_key",
            "a soft ref to feature_flag.flag_key (V45) — the name of a flag, not a place bytes live");

    private static List<String> objectKeyColumns() {
        return TenantObjectExtractionService.OBJECT_KEY_COLUMNS.stream()
                .map(column -> column.table() + "." + column.column())
                .toList();
    }

    /** One extracted object held in memory, so the bundle can be replayed after the store is wiped. */
    private record Copy(String key, String contentType, long size, byte[] bytes) {

        static Copy of(StoredObject object) {
            try {
                return new Copy(object.key(), object.contentType(), object.sizeBytes(),
                        object.content().readAllBytes());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("could not read " + object.key(), failure);
            }
        }

        StoredObject open() {
            return new StoredObject(key, contentType, size, new ByteArrayInputStream(bytes));
        }
    }

    /** Runs the extraction on the org's own axis and collects it, which is what a test sink is for. */
    private Map<String, byte[]> extract(UUID orgId) {
        Map<String, byte[]> collected = new LinkedHashMap<>();
        TenantContext.runAs(orgId, () -> objects.extract(orgId,
                object -> collected.put(object.key(), Copy.of(object).bytes())));
        return collected;
    }

    private String put(String key, String contentType, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(payload), payload.length, contentType);
        return key;
    }

    /**
     * A {@code document} row written around {@link ug.co.smsone.shared.document.NewDocument}'s guard,
     * which is the only way to produce the shapes these tests are about — and the same way a legacy row
     * or a migration would produce them.
     */
    private UUID insertDocument(UUID axis, UUID orgId, String storageKey) {
        UUID id = UUID.randomUUID();
        TenantContext.runAs(axis, () -> jdbc.update("""
                insert into document (id, org_id, owner_person_id, storage_key, name, content_type,
                                      size_bytes, source, version, created_at)
                values (?, ?, ?, ?, 'file.pdf', 'application/pdf', 12, 'UPLOAD', 0, now())
                """, id, orgId, UUID.randomUUID(), storageKey));
        return id;
    }
}
