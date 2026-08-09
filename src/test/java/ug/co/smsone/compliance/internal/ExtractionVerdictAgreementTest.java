package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ug.co.smsone.testsupport.ExtractionVerdicts;

/**
 * <strong>The two records of what an extraction does with a platform table must be one record.</strong>
 *
 * <h2>The bug this test is the fix for, because it shipped</h2>
 *
 * <p>{@link TenantBundlePlan} judged every table in {@code platform} and refused to plan a bundle while
 * any of them had no judgement — a good rule, correctly enforced. Separately,
 * {@code TenantSchemaSelfContainmentTest} kept its own map of the org-scoped subset, to answer the
 * question the tier assertions structurally cannot: a table can be correctly platform-tier and still
 * hold rows naming one organization, and those rows either travel or are lost.
 *
 * <p>They disagreed on two tables and both shipped green. {@code legal_hold}: the plan said it stays and
 * {@code TenantBundler} refused an organization under a live hold; the test said it travels, copied the
 * row into the extracted deployment by hand, and asserted end to end that the restored purge was blocked
 * by a hold that had come across — a property no production path performs. {@code api_usage_daily}: the
 * same shape, stays against travels. Nothing failed, because the self-containment test hand-carries its
 * platform half with its own SQL and never invokes the bundler. Two answers, opposite operational
 * consequences, no recorded winner and no failing test — which is the same structural gap that let the
 * placement seam survive, and it is disclosed in ADR 0010 §7 Phase 6.
 *
 * <p>The judgement now lives in {@link TenantBundlePlan} alone. {@link ExtractionVerdicts} is the
 * promotion-side mirror of the part that test needs, and this is what makes a mirror safe: it can no
 * longer say anything the shipped plan does not say.
 *
 * <h2>Deliberately not an integration test</h2>
 *
 * <p>{@link TenantBundlePlan#declared} reads the compiled judgement, not the catalogue, so this needs no
 * database and no context — which is what lets it fail in seconds on a laptop rather than eight minutes
 * into a suite. The catalogue half of the question (is every org-scoped platform table judged AT ALL?)
 * is asked twice against a real database and belongs there: {@link TenantBundlePlan#plan()} refuses an
 * unjudged table, and {@code TenantSchemaSelfContainmentTest} compares
 * {@link ExtractionVerdicts#tables()} against {@code information_schema}.
 */
class ExtractionVerdictAgreementTest {

    /**
     * The vocabularies are the same vocabulary. A constant on one side and not the other is how a mirror
     * starts drifting while every existing entry still lines up — and {@code Verdict.valueOf(...)} below
     * would then fail with a message about an enum rather than about a bundle.
     */
    @Test
    void theTwoEnumerationsNameTheSameDispositions() {
        assertThat(Arrays.stream(ExtractionVerdicts.Verdict.values())
                .map(Enum::name).toList())
                .describedAs("ExtractionVerdicts.Verdict mirrors BundleDisposition by name; a constant"
                        + " added to one and not the other makes the agreement check below unable to"
                        + " express the new answer")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(BundleDisposition.values()).map(Enum::name).toList());
    }

    /**
     * <strong>Table for table, the same answer.</strong> Both directions matter and the second is the one
     * that caught nothing for a whole phase: a mirror entry for a table the plan does not judge is a
     * judgement about nothing, and it is exactly what {@code legal_hold} became the day the plan said
     * STAYS and the mirror kept saying TRAVELS.
     */
    @Test
    void everyOrgScopedPlatformTableCarriesTheSameDispositionInBothRecords() {
        Map<String, BundleDisposition> shipped = new LinkedHashMap<>();
        for (BundleDisposition disposition : BundleDisposition.values()) {
            for (String table : TenantBundlePlan.declared(disposition)) {
                shipped.put(table, disposition);
            }
        }

        Map<String, BundleDisposition> mirrored = new LinkedHashMap<>();
        ExtractionVerdicts.all().forEach((table, verdict) ->
                mirrored.put(table, BundleDisposition.valueOf(verdict.name())));

        List<String> unknownToThePlan = mirrored.keySet().stream()
                .filter(table -> !shipped.containsKey(table)).sorted().toList();
        assertThat(unknownToThePlan)
                .describedAs("ExtractionVerdicts judges platform table(s) TenantBundlePlan has never"
                        + " heard of. The bundler is the artifact that runs in production; a verdict it"
                        + " does not share is a sentence in a test file and nothing else")
                .isEmpty();

        Map<String, BundleDisposition> shippedForTheseTables = new LinkedHashMap<>();
        mirrored.keySet().stream().sorted()
                .forEach(table -> shippedForTheseTables.put(table, shipped.get(table)));
        Map<String, BundleDisposition> mirroredSorted = new LinkedHashMap<>();
        mirrored.keySet().stream().sorted().forEach(table -> mirroredSorted.put(table, mirrored.get(table)));

        assertThat(mirroredSorted)
                .describedAs("the extraction disposition of a platform table is ONE decision. It was two"
                        + " for the length of Phase 6 — legal_hold and api_usage_daily carried opposite"
                        + " answers in TenantBundlePlan and in the self-containment test's verdict map,"
                        + " and both shipped green because that test hand-carries its platform half"
                        + " instead of calling the bundler (ADR 0010 §7). Change TenantBundlePlan, then"
                        + " change ExtractionVerdicts to match it — never the other way round")
                .isEqualTo(shippedForTheseTables);
    }
}
