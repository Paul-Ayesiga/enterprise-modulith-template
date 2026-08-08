package ug.co.smsone.shared.tenancy.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The placement default is written down twice — in {@code application.yaml} and in
 * {@link TenantPlacementProperties}' compact constructor — and this asserts the two agree.
 *
 * <p><b>Why that is worth a test.</b> Two defaults that disagree do not fail anywhere. Every normal
 * boot loads the yaml and behaves correctly; only a context that never loads it falls through to the
 * constructor and gets the other answer. So the disagreement surfaces as a subset of tests provisioning
 * tenants into a different home than production does — which reads as a flaky test, or as nothing at
 * all, right up until an environment is configured the other way. The two values were in fact out of
 * step for exactly as long as it took to write this file.
 *
 * <p>No Spring context and no database: the question is whether two literals match, and booting a
 * context to ask it would make the test slower without making it stricter. It parses the yaml as text
 * rather than through a {@code Binder} deliberately — binding would resolve the placeholder through
 * whatever {@code TENANT_PLACEMENT_POLICY} happens to be set to in the environment running the suite,
 * and the property under test is the <em>fallback written in the file</em>, not the resolved value.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlacementPolicyDefaultTest {

    /** {@code policy: ${TENANT_PLACEMENT_POLICY:silo-per-org}} — captures just the fallback. */
    private static final Pattern YAML_DEFAULT =
            Pattern.compile("policy:\\s*\\$\\{TENANT_PLACEMENT_POLICY:([a-z-]+)}");

    @Test
    void theYamlFallbackAndTheConstructorFallbackNameTheSamePolicy() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        Matcher matcher = YAML_DEFAULT.matcher(yaml);

        assertThat(matcher.find())
                .as("application.yaml no longer declares app.tenancy.placement.policy in the form this"
                        + " test can read. That is not necessarily wrong — but the two defaults are now"
                        + " unchecked, so fix the pattern rather than deleting the test.")
                .isTrue();

        // Relaxed binding is what turns the yaml's kebab-case into the enum constant at runtime, so the
        // comparison has to go through the same transformation rather than an exact string match.
        PlacementPolicy fromYaml =
                PlacementPolicy.valueOf(matcher.group(1).toUpperCase().replace('-', '_'));

        assertThat(new TenantPlacementProperties(null).policy())
                .as("application.yaml defaults placement to %s; TenantPlacementProperties defaults it to"
                        + " something else. A context that does not load the yaml will provision tenants"
                        + " into the wrong home, and nothing will fail to say so.", fromYaml)
                .isEqualTo(fromYaml);
    }

    /**
     * Worth its own assertion: {@link PlacementPolicy#buildsASchema()} is the question
     * {@code TenantProvisioner} asks to decide whether a schema must exist BEFORE
     * {@code OrganizationRegistered} is published. Under the shipped silo-per-org default the answer is
     * yes, and it has to be: the three after-commit listeners on that event (trial, billing account,
     * search document) all write tenant-tier tables, so a schema created after the commit is a schema
     * they race — and because they are outbox-retried, the failures look like transient noise rather
     * than a broken signup (ADR 0010 §4.3). Under {@link PlacementPolicy#POOLED} the answer is no,
     * because {@code tenant_pool} is already there and a signup stays one atomic write.
     *
     * <p>The expectation is written as {@code policy != POOLED} rather than as a literal so that the
     * assertion keeps meaning something the day a third policy is added: what is being checked is that
     * the flag and the rule agree, not what today's default happens to be.
     */
    @Test
    void theDefaultPolicyKnowsWhetherItHasToBuildSomethingBeforeAnnouncingTheTenant() {
        PlacementPolicy policy = new TenantPlacementProperties(null).policy();

        assertThat(policy.buildsASchema())
                .as("%s must report whether provisioning has to create a schema before the tenant is"
                        + " announced; TenantProvisioner branches on exactly this.", policy)
                .isEqualTo(policy != PlacementPolicy.POOLED);
    }
}
