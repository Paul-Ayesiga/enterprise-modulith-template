package ug.co.smsone.compliance.internal;

import java.io.StringWriter;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import ug.co.smsone.shared.tenancy.TenantRoutes;

/**
 * <strong>The bundle an operator actually carries — the shipped {@code TenantBundler}'s output, written
 * through the shipped {@code BundleScriptWriter}, handed out as the SQL text and nothing else.</strong>
 * It exists so that ADR 0010 §6's acceptance test can restore <em>the artifact</em> rather than SQL it
 * wrote for itself.
 *
 * <h2>The gap this closes, because it is the reason the class is here at all</h2>
 *
 * <p>{@code shared.tenancy.promotion.TenantSchemaSelfContainmentTest} is §6's gate: dump a tenant,
 * restore it into a database that has never heard of the platform, and make it serve. Until now its
 * platform half was ninety lines of hand-written {@code copyRows(...)} in the test itself, so every
 * far-side assertion it made was true of <em>a</em> bundle and none of them was true of <em>the</em>
 * bundle. The whole gate stayed green with {@code TenantBundler} deleted — the shape this project has
 * been bitten by before, and the reason ADR 0010 §7 Phase 6 records the work as unfinished in the
 * sentence "running the self-containment test's far side against the bundler's actual emitted output
 * rather than a hand-built one". This is that seam.
 *
 * <h2>Why a class in this package, and why it is a TEST class</h2>
 *
 * <p>{@link TenantBundler}, {@link BundleScriptWriter} and {@link TenantBundler.Manifest} are
 * package-private in {@code compliance.internal}, deliberately: the bundler reads every secret the
 * tenant owns and is destructive in {@code CUTOVER}, so the only thing that may name it is code that
 * lives beside it. {@link ug.co.smsone.testsupport.ExtractionVerdicts} already records the decision
 * that follows from that — widening those types to public "<em>would put a module's internals on the
 * compile path of another module's test</em>" — and that decision is unchanged here. Nothing in
 * {@code src/main} gains a public member.
 *
 * <p>What this is instead is the ordinary Java answer: a class in the owner's own package, in the TEST
 * source root, whose entire public surface is {@link String}, {@link List} and {@link UUID}. The
 * self-containment test compiles against those and cannot name a single compliance type through it.
 * The alternative shapes were both worse. Moving §6 part (c) into this package would have had to leave
 * its export assertion behind — that one drives the real {@code TenantTierTables} planner against the
 * restored schema, and {@code TenantTierTables}' constructor, {@code quote} and {@code ROW_ALIAS} are
 * all package-private in {@code shared.tenancy.promotion} — and a public port in the {@code compliance}
 * API package would be a production entry point invented for a test, which AGENTS §2.1 forbids
 * ("<em>make a class public only when a cross-module port or Spring's proxying forces it</em>") and
 * which nothing in {@code src/main} would call.
 *
 * <h2>REHEARSAL, and what that costs the gate</h2>
 *
 * <p>{@link TenantBundler.Mode#REHEARSAL}: item 8 counts the keys a cutover would revoke and revokes
 * none of them. {@code api_key} is {@code REMINTED} and carries no rows in either mode, but the two
 * scripts are NOT byte-identical — a CUTOVER writes the credential-retirement row into the tenant's own
 * {@code audit_log} before the schema is read, so its script carries exactly that row more. What this
 * gate therefore proves is the REHEARSAL artifact; the delta a cutover adds — the revocations and the
 * audit row recording them — is owned by {@code TenantBundleTest}'s CUTOVER path. The trade is
 * deliberate: a gate that killed a tenant's machine credentials every time it ran is a gate somebody
 * eventually stops running, and the price is that one row of one part is proven in a different file.
 *
 * @param script the SQL an operator hands to {@code psql -v ON_ERROR_STOP=1 -f}, manifest and all
 * @param sourceSchema the tenant home the bundle was taken out of
 * @param rows how many rows the manifest accounts for, across all eleven parts
 * @param warnings the manifest's warnings — things worth saying that do not invalidate the bundle
 * @param doesNotTravel one line per platform table that stays behind, with what the tenant loses by it
 */
public record TenantBundleArtifact(String script, String sourceSchema, int rows,
        List<String> warnings, List<String> doesNotTravel) {

    /**
     * Takes the bundle for {@code orgId} and returns it as the file an operator would restore.
     *
     * <p>The whole runbook shape, in the runbook's order (docs/runbooks/tenant-extraction.md, "Taking
     * it"): preamble, stream the eleven parts through the script writer, acknowledge the object bytes,
     * {@code requireComplete()}, epilogue. <b>{@code requireComplete()} is an assertion and is called
     * here on purpose</b> — a manifest that has not been told what the object extractor took throws,
     * so a caller cannot end up restoring a bundle that is silently short of item 7.
     *
     * <p>The acknowledgement is {@code (0, 0)} and that is honest rather than convenient: a database
     * test takes no bytes, a count of zero is a legitimate answer for an organization with no
     * documents, and §6 item 7's own contract is that silence — not zero — is what is forbidden.
     *
     * @throws IllegalStateException from the bundler itself, unchanged and unwrapped: a live legal
     *     hold, an undrained queue, a subscription naming a plan the catalogue does not hold, a
     *     transaction already open, a thread already on a tenant axis. Callers assert on these.
     */
    public static TenantBundleArtifact take(ApplicationContext beans, UUID orgId) {
        TenantBundler bundler = beans.getBean(TenantBundler.class);
        String sourceSchema = TenantRoutes.homeOf(orgId);
        StringWriter script = new StringWriter();
        TenantBundler.Manifest manifest;
        try (BundleScriptWriter writer = new BundleScriptWriter(script)) {
            writer.preamble(orgId, sourceSchema, TenantBundler.Mode.REHEARSAL);
            manifest = bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, writer)
                    .withObjectBytes(new TenantBundler.Manifest.ObjectBytes(0, 0));
            manifest.requireComplete();
            writer.epilogue(manifest);
        }
        return new TenantBundleArtifact(script.toString(), sourceSchema, manifest.rowsBundled(),
                manifest.warnings(), manifest.doesNotTravel());
    }
}
