package ug.co.smsone.compliance.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.files.ObjectPage;
import ug.co.smsone.files.StoredObject;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.document.OrgObjectPrefixes;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * <b>Item 7 of ADR 0010 §6's extraction bundle: one organization's bytes, out of the object store and
 * back in again.</b> {@link TenantExtractionService} owns item 1 — the tenant's rows — and this owns the
 * only other part of a tenant that does not live in Postgres. Neither is the bundle; each is one part of
 * it, complete, so that the bundler above them can be assembled from parts that are individually
 * checkable.
 *
 * <h2>The store is addressed by PREFIX, and that is the whole design</h2>
 *
 * <p>A bucket is a flat key space with no {@code WHERE org_id = ?}. What separates two tenants' objects
 * is the shape of the key and nothing else, so extraction is a listing of
 * {@link OrgObjectPrefixes#forOrg} — {@code doc/o/<orgId>/} and {@code exch/o/<orgId>/} today — and it
 * takes <b>whatever it finds there, whatever any row says</b>. That asymmetry is deliberate and it cuts
 * both ways: an orphaned object whose {@code document} row was purged still travels (the tenant paid for
 * those bytes and a restore has to be faithful), and a personal file that somehow sat under an
 * organization's prefix would travel too — which is why {@code NewDocument} refuses to file one there.
 *
 * <p><b>The database is not the source of truth here; it is the DETECTOR.</b> Before a single byte
 * moves, {@link #extract} asks the tenant's own schema for every object key it has recorded and refuses
 * the extraction if any of them falls outside the prefixes about to be listed. That check is what turns
 * the one failure this design cannot otherwise see — a future minter inventing a third org-scoped root —
 * from a bundle that is quietly short into an extraction that will not start. It is the same doctrine
 * {@link TenantExtractionService} states for an unroutable table and {@code SoftDeletePurgeJob} states
 * as "loud AND complete": short and silent is the one outcome to design out.
 *
 * <h2>What promotion does to the object store: nothing</h2>
 *
 * <p>Worth saying because it is the natural next question. ADR 0010 §6 hop 0→1 moves a tenant's rows
 * from {@code tenant_pool} into {@code t_<hex>}, and the object keys do not mention the schema — they
 * mention the organization, which does not change. So a promotion copies no bytes, and this class has no
 * part in it. The object store only moves at hop 2→3, when the tenant gets a deployment (and therefore a
 * bucket) of its own.
 *
 * <h2>Why {@link #restore} exists in the same class as {@link #extract}</h2>
 *
 * <p>Because an extraction nobody has ever replayed is a backup nobody has ever restored. The two are
 * one mechanism read in two directions, they share the one guard that matters — a key must belong to the
 * organization it is travelling with — and keeping them apart is how the two drift until the day the
 * bundle is needed. The rehearsal in ADR 0010 §7 Phase 6 is exactly this pair run back to back.
 *
 * <h2>No HTTP route reaches this, for {@link TenantExtractionService}'s reasons</h2>
 *
 * <p>The output is unbounded and it is the tenant's data verbatim; both disqualify it from being a JSON
 * response (ADR 0002), and neither is fixable by capping or redacting — that would be
 * {@link GdprBundleService} again. It is an operator runbook step.
 */
@Service
class TenantObjectExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TenantObjectExtractionService.class);

    /**
     * S3's own maximum for one listing. A smaller page would only add round trips: the page carries
     * keys, never bytes, so a thousand of them is a few hundred kilobytes and the objects themselves are
     * streamed one at a time regardless.
     */
    private static final int PAGE_SIZE = 1000;

    /** How many offending keys a refusal quotes before it stops — enough to see the pattern. */
    private static final int OFFENDERS_QUOTED = 10;

    /**
     * Every column in a tenant schema that holds an object-store key. Hand-named, and the derivation
     * that would have avoided that does not exist: "ends in {@code _key}" also catches
     * {@code integration_setting.setting_key}, which is a settings NAME whose values are things like
     * {@code webhook.url} and would fail the prefix check on every row. So the list is stated, and
     * {@code TenantObjectExtractionTest.everyKeyShapedColumnInTheTenantSchemaIsClassified} enumerates
     * the schema and fails the build when a new one appears undeclared — the same shape as
     * {@code SoftDeletePurgeJobIntegrationTest.purgeOrderCoversEverySoftDeletableEntity}.
     *
     * <p>All four are also filed as {@code document} rows today ({@code ArtifactStore.store} registers
     * one for every artifact it writes, source and result and error report alike), so
     * {@code document.storage_key} alone would find the same keys. The exchange columns are listed
     * anyway because they are written by a different statement than the registration is: the bytes go up,
     * then the row is filed, and a crash between the two leaves the job row naming a key no document
     * knows about.
     */
    static final List<KeyColumn> OBJECT_KEY_COLUMNS = List.of(
            new KeyColumn("document", "storage_key"),
            new KeyColumn("exchange_job", "source_key"),
            new KeyColumn("exchange_job", "result_key"),
            new KeyColumn("exchange_job", "error_report_key"));

    /** A tenant-schema column holding an object key, and the {@code org_id} that says whose row it is. */
    record KeyColumn(String table, String column) {

        /**
         * Rows of this tenant, <b>plus any row in this schema that claims no organization at all</b>. The
         * second arm is not defensive padding: {@code document} and {@code exchange_job} are two of the
         * seven split tables, so a NULL {@code org_id} row sitting in a tenant's schema is a row whose org
         * disagrees with its home — ADR 0010 §1's worst failure — and its key names a person, not this
         * tenant. Finding it here is finding it at the one moment it is about to be acted on.
         *
         * <p><b>Soft-deleted rows are deliberately NOT filtered out</b>, which is the decision AGENTS §14
         * requires native SQL over a soft-deletable table to make out loud ({@code @SQLRestriction} does
         * not reach here). This asks about the SHAPE of a key, never about which bytes exist: a row
         * recording a key no prefix would find is evidence of a minter nobody declared whether it is live
         * or not, and the same minter is writing live rows. Filtering would narrow the one check that can
         * see that class of bug, to spare an operator a refusal whose repair — one root added to
         * {@link OrgObjectPrefixes} — is additive and takes a minute. The reverse mistake is silent and
         * permanent, so the asymmetry decides it. ({@code DocumentService.delete} removes the object
         * before soft-deleting the row, so a deleted row's key addresses nothing; the listing simply
         * never finds it, and nothing here depends on that.)
         */
        String sql() {
            return "select " + column + " from " + table
                    + " where " + column + " is not null and (org_id = ? or org_id is null)"
                    + " order by " + column;
        }
    }

    /** One object of the bundle, open for the duration of the call. See {@link StoredObject}. */
    @FunctionalInterface
    interface ObjectSink {

        /**
         * Hands over one object. The stream is live and belongs to whoever opened it — do not retain it
         * past this call, and do not close it: {@link #extract} closes what it opened, and an
         * {@link ObjectSource} closes what it opened.
         */
        void object(StoredObject object);
    }

    /**
     * A bundle being read back. Push rather than pull, so the reader owns each object's lifetime and can
     * close it the moment the sink returns — an iterator would have to hold one open across the caller's
     * loop body, and a bundle of a hundred thousand objects would then depend on the caller's diligence.
     */
    @FunctionalInterface
    interface ObjectSource {
        void forEach(ObjectSink sink);
    }

    /**
     * What moved: the prefixes visited, and the objects and bytes that came out of (or went into) them.
     * The counts are a by-product of streaming, never a second pass — a count taken separately from the
     * copy is a count of a different instant.
     */
    record ObjectManifest(UUID orgId, List<String> prefixes, int objects, long bytes) {

        ObjectManifest {
            prefixes = List.copyOf(prefixes);
        }
    }

    private final FileStorageProvider storage;
    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;

    TenantObjectExtractionService(FileStorageProvider storage, JdbcTemplate jdbc, AuditLog auditLog) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    /**
     * Streams every object under {@code orgId}'s prefixes to {@code sink}, in lexicographic key order.
     *
     * <p>Runs pinned to the tenant, because the completeness check reads the tenant's own copy of
     * {@code document} and {@code exchange_job} and those are split tables — asked from the platform axis
     * it would check the platform's copies, which hold other tenants' platform-scoped jobs and every
     * human's personal documents, and the answer would be about the wrong rows entirely.
     *
     * <p>Not transactional and not snapshot-consistent, for {@link TenantExtractionService#extract}'s
     * reason and one more of its own: the object store has no transactions at all. Consistency is the
     * freeze window's job (ADR 0010 §6 hop 0→1) — with the tenant frozen and its queues paused nothing is
     * writing these keys. Without the freeze an object listed on page one can be deleted before it is
     * opened, and this fails loudly with {@code FileNotFoundException} rather than skipping it, because a
     * bundle that silently omits an object is the failure this whole class exists to prevent.
     *
     * @throws IllegalStateException if the thread is not pinned to {@code orgId}, or if the tenant has
     *     recorded an object key that no declared prefix covers
     */
    ObjectManifest extract(UUID orgId, ObjectSink sink) {
        requirePinnedTo(orgId);
        refuseKeysNoPrefixWouldFind(orgId);
        List<String> prefixes = OrgObjectPrefixes.forOrg(orgId);
        int[] objects = {0};
        long[] bytes = {0};
        for (String prefix : prefixes) {
            // A page at a time, never a key list built first. The list would be the one part of an
            // extraction that scales with the tenant rather than with the page: at a million objects it is
            // ~100 MB of strings held for the whole copy, in a process that is also streaming the bytes.
            forEachKeyUnder(prefix, PAGE_SIZE, key -> {
                try (StoredObject object = storage.open(key)) {
                    sink.object(object);
                    objects[0]++;
                    bytes[0] += object.sizeBytes();
                }
            });
        }
        log.info("Extracted {} objects ({} bytes) for org {} from prefixes {}", objects[0], bytes[0], orgId,
                prefixes);
        // Its own action rather than a detail on compliance.tenant_extracted: the two artifacts leave by
        // different routes and can fail independently, and the question this trail has to answer — did
        // this organization's FILES leave, and when — cannot be answered by a row about its rows.
        auditLog.record("compliance.tenant_objects_extracted", orgId, orgId.toString(), null,
                "prefixes=" + prefixes.size() + " objects=" + objects[0] + " bytes=" + bytes[0]);
        return new ObjectManifest(orgId, prefixes, objects[0], bytes[0]);
    }

    /**
     * Writes a bundle's objects back under their own keys — the far side of an extraction, and the way
     * back from one.
     *
     * <p><b>Every key is checked against {@code orgId}'s prefixes, and that check is the point of this
     * method existing at all</b> rather than the caller looping over {@code FileStorageProvider.write}.
     * A bundle is a file somebody moved between machines; the one thing a restore must never do is take
     * an object that belongs to another tenant on the word of the bundle it arrived in. The extraction's
     * guard is a mirror of it, so a bundle this refuses could not have been produced here.
     *
     * <p>Unlike {@link #extract} this needs no tenant axis: it writes bytes and reads no row, so there is
     * no schema for an axis to choose. Requiring one would be a ceremony with no correctness behind it —
     * and it would be actively wrong on the far side, where the restore runs before the tenant's schema
     * is necessarily routable at all.
     *
     * <p>Idempotent: a key written twice is the same object, so a half-finished restore is finished by
     * running it again.
     *
     * @throws IllegalStateException if the bundle carries an object outside {@code orgId}'s prefixes
     */
    ObjectManifest restore(UUID orgId, ObjectSource source) {
        List<String> prefixes = OrgObjectPrefixes.forOrg(orgId);
        int[] objects = {0};
        long[] bytes = {0};
        source.forEach(object -> {
            requireBelongsTo(orgId, object.key(), prefixes);
            storage.write(object);
            objects[0]++;
            bytes[0] += object.sizeBytes();
        });
        log.info("Restored {} objects ({} bytes) for org {} under prefixes {}", objects[0], bytes[0],
                orgId, prefixes);
        auditLog.record("compliance.tenant_objects_restored", orgId, orgId.toString(), null,
                "prefixes=" + prefixes.size() + " objects=" + objects[0] + " bytes=" + bytes[0]);
        return new ObjectManifest(orgId, prefixes, objects[0], bytes[0]);
    }

    /**
     * Every key under one prefix, collected. The <b>test seam</b> for {@link #forEachKeyUnder}, and nothing
     * in the extraction path calls it: {@link #PAGE_SIZE} is the store's maximum, so proving that the loop
     * crosses a page boundary at that size would mean a test uploading a thousand objects. Driving the
     * same loop at three is the cheaper honest answer — and the alternative, trusting the boundary because
     * it is "obviously" right, is how a bundle ends up short by exactly one page.
     *
     * <p>It collects and {@link #extract} does not, which is the whole reason the loop lives elsewhere: a
     * test holds seven keys, an extraction would hold the tenant's entire key space.
     */
    List<String> keysUnder(String prefix, int pageSize) {
        List<String> keys = new ArrayList<>();
        forEachKeyUnder(prefix, pageSize, keys::add);
        return List.copyOf(keys);
    }

    /**
     * Walks every key under one prefix, a page at a time, in lexicographic order.
     *
     * <p>Terminates on the store's own {@code isTruncated} rather than on a short page — see
     * {@link ObjectPage}, where the difference between the two is the difference between a complete bundle
     * and a quietly short one. Paging is keyset (the last key of a page is the next page's
     * {@code startAfter}), which is what makes it safe to do work between pages: there is no offset for a
     * concurrent write to shift.
     */
    private void forEachKeyUnder(String prefix, int pageSize, Consumer<String> key) {
        String after = null;
        while (true) {
            ObjectPage page = storage.list(prefix, after, pageSize);
            page.keys().forEach(key);
            if (!page.more()) {
                return;
            }
            if (page.lastKey() == null) {
                // A store claiming more while handing back nothing gives the keyset cursor nothing to
                // advance on, so the loop would spin forever on the same request. Refuse: an extraction
                // that hangs is worse than one that names what it could not page.
                throw new IllegalStateException("the object store reports more keys after prefix '" + prefix
                        + "' but returned an empty page, so there is no cursor to continue from. The"
                        + " extraction cannot prove it saw every object and refuses to produce a bundle"
                        + " that might be short.");
            }
            after = page.lastKey();
        }
    }

    /**
     * The completeness check, and the only thing standing between a third org-scoped namespace and a
     * bundle that quietly leaves its bytes behind.
     *
     * <p>It reads the tenant's own recorded keys and asks one question of each: would the listing about
     * to run have found it? A no has exactly two causes and the message names both, because the repairs
     * are opposite. Either a minter invented a root nobody declared — one line in
     * {@link OrgObjectPrefixes} and it is extracted from then on — or a row belonging to a human is
     * sitting in a tenant's schema, which is a misroute (ADR 0010 §1) and must be moved, never
     * accommodated by widening the prefix.
     */
    private void refuseKeysNoPrefixWouldFind(UUID orgId) {
        Set<String> offenders = new LinkedHashSet<>();
        for (KeyColumn column : OBJECT_KEY_COLUMNS) {
            for (String key : jdbc.queryForList(column.sql(), String.class, orgId)) {
                if (!OrgObjectPrefixes.covers(orgId, key)) {
                    offenders.add(column.table() + "." + column.column() + " = " + key);
                }
            }
        }
        if (offenders.isEmpty()) {
            return;
        }
        List<String> quoted = offenders.stream().limit(OFFENDERS_QUOTED).toList();
        throw new IllegalStateException("organization " + orgId + " has recorded " + offenders.size()
                + " object key(s) that none of its declared prefixes " + OrgObjectPrefixes.forOrg(orgId)
                + " would find, so an extraction would copy the rows and leave the bytes behind — silently,"
                + " and only discoverable on the far side as a download that 404s. Either the key was minted"
                + " under a root that is not declared (add it to OrgObjectPrefixes.ROOTS, which is all it"
                + " takes for the extraction and the restore to cover it), or it names a PERSON and its row"
                + " is in a tenant schema it does not belong to, which is a misroute and is fixed by moving"
                + " the row, never by widening the prefix. Offending keys: " + quoted
                + (offenders.size() > quoted.size() ? " (and " + (offenders.size() - quoted.size()) + " more)"
                        : ""));
    }

    private static void requireBelongsTo(UUID orgId, String key, List<String> prefixes) {
        if (!OrgObjectPrefixes.covers(orgId, key)) {
            throw new IllegalStateException("the bundle for organization " + orgId + " carries an object"
                    + " outside that organization's prefixes " + prefixes + ", so restoring it would write"
                    + " another owner's bytes into this tenant — or this tenant's into a namespace no"
                    + " extraction of it would ever find again. Key: " + key);
        }
    }

    /**
     * Refuses an extraction taken from the wrong axis, exactly as {@link TenantExtractionService} does and
     * for the same two reasons. From {@code Tenant.PLATFORM} the completeness check would read the
     * platform copies of the split tables — every human's personal documents — and report them all as
     * offending keys. From another tenant's axis it would check that tenant's keys and pass, then copy
     * this one's bytes with a completeness guarantee that was never about them.
     */
    private static void requirePinnedTo(UUID orgId) {
        Tenant current = TenantContext.current();
        if (!(current instanceof Tenant.Org(UUID pinned)) || !pinned.equals(orgId)) {
            throw new IllegalStateException("an object extraction verifies itself against the tenant's own"
                    + " document and exchange rows, so it must run pinned to that tenant: wrap the call in"
                    + " TenantContext.runAs(" + orgId + ", …). The thread is currently on " + current + ".");
        }
    }
}
