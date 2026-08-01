package ug.co.smsone.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Every operation is tagged {@code "<axis> · <resource>"}, and the two halves are pinned differently
 * here on purpose — the asymmetry is the design, not an accident of how the test was written.
 *
 * <p>The <b>axis</b> is derived from each handler's {@code @PreAuthorize}, so it is a claim about who
 * may call the operation and is asserted by rule: everything under {@code /admin} is Platform, the
 * tenant surface is Organization, and the routes where the two axes meet on one path or one controller
 * land on opposite sides. The risk being covered is not that it is wrong today, it is that it silently
 * stops matching the code later — which a list of expected paths would hide, because such a list gets
 * edited to match whatever the build now produces.
 *
 * <p>The <b>resource</b> half is a curated label whose worst failure is a request filed in the wrong
 * folder, so no test names one. What is pinned instead is that the labelling stays coherent: every
 * group an operation uses is declared with a description, every declared group is used, and no group
 * grows back into the unscannable list this scheme exists to break up.
 *
 * <p>Grouping is only half of what makes the published spec readable, so the operation's own
 * {@code summary} is pinned here too rather than in a class of its own: <em>which folder</em> and
 * <em>what the request is called</em> are the same complaint — a Postman import nobody can scan — and
 * splitting them would mean two Spring contexts pinning one contract. Summaries are asserted by rule,
 * never against a list of expected strings, for the same reason the axis is.
 */
@AutoConfigureMockMvc
class OpenApiTagContractTest extends AbstractIntegrationTest {

    private static final List<String> AXES = List.of(
            OpenApiConfig.AXIS_PLATFORM, OpenApiConfig.AXIS_ORGANIZATION, OpenApiConfig.AXIS_SHARED);

    /**
     * Above this a Postman folder stops being something you scan and becomes something you search —
     * which is the complaint that produced this scheme (one Organization group held 18). It is a
     * readability budget, so when a group outgrows it, split the resource label; do not raise the number.
     */
    private static final int MAX_OPERATIONS_PER_GROUP = 8;

    /**
     * Postman and Swagger UI both render a request name on one line beside everything else in its
     * folder, and a longer one is truncated there — so the words past this budget are not read by
     * anyone. When a summary outgrows it, move the detail into {@code description}, which is the field
     * that has room; do not raise the number.
     */
    private static final int MAX_SUMMARY_LENGTH = 70;

    @Autowired
    private MockMvc mockMvc;

    private JsonNode spec() throws Exception {
        return new ObjectMapper().readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** operation ("GET /api/v1/...") -> its tags. */
    private Map<String, List<String>> operationTags(JsonNode spec) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        spec.path("paths").properties().forEach(path ->
                path.getValue().properties().forEach(verb -> {
                    List<String> tags = new ArrayList<>();
                    verb.getValue().path("tags").forEach(t -> tags.add(t.asText()));
                    out.put(verb.getKey().toUpperCase() + " " + path.getKey(), tags);
                }));
        return out;
    }

    /** operation ("GET /api/v1/...") -> its summary; a missing one reads as "" and fails as blank. */
    private Map<String, String> operationSummaries(JsonNode spec) {
        Map<String, String> out = new LinkedHashMap<>();
        spec.path("paths").properties().forEach(path ->
                path.getValue().properties().forEach(verb ->
                        out.put(verb.getKey().toUpperCase() + " " + path.getKey(),
                                verb.getValue().path("summary").asText())));
        return out;
    }

    /** The root tags list, in declared order. */
    private List<String> declaredGroups(JsonNode spec) {
        List<String> names = new ArrayList<>();
        spec.path("tags").forEach(tag -> names.add(tag.path("name").asText()));
        return names;
    }

    private static Map<String, List<String>> filterKeys(Map<String, List<String>> all,
            Predicate<String> keep) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        all.forEach((operation, tags) -> {
            if (keep.test(operation)) {
                out.put(operation, tags);
            }
        });
        return out;
    }

    /** The one group an operation carries — a missing operation or tag fails as an assertion, not a throw. */
    private static String groupOf(Map<String, List<String>> tags, String operation) {
        assertThat(tags).as("no such operation: %s", operation).containsKey(operation);
        assertThat(tags.get(operation)).as("%s", operation).hasSize(1);
        return tags.get(operation).getFirst();
    }

    private static String axisOf(String group) {
        int separator = group.indexOf(OpenApiConfig.AXIS_SEPARATOR);
        return separator < 0 ? group : group.substring(0, separator);
    }

    @Test
    void everyOperationCarriesExactlyOneDeclaredGroup() throws Exception {
        JsonNode spec = spec();
        Set<String> declared = Set.copyOf(declaredGroups(spec));
        Map<String, List<String>> tags = operationTags(spec);

        assertThat(tags).isNotEmpty();
        assertThat(tags).allSatisfy((operation, applied) ->
                assertThat(applied).as("%s", operation).hasSize(1).allMatch(declared::contains));
    }

    /**
     * Both directions, because each catches a different mistake: an operation tagged with something the
     * root list never declares sorts outside the deliberate order and reaches Postman as a folder with
     * no description, while a declared group nobody uses means a curated label stopped matching its
     * controller (a rename, most likely) and those operations quietly fell back to another group.
     */
    @Test
    void everyDeclaredGroupIsUsedAndEveryUsedGroupIsDeclared() throws Exception {
        JsonNode spec = spec();
        List<String> declared = declaredGroups(spec);
        Set<String> used = new LinkedHashSet<>(operationTags(spec).values().stream()
                .flatMap(List::stream).toList());

        assertThat(declared).doesNotHaveDuplicates();
        assertThat(used).as("groups used by an operation but never declared")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    /**
     * springdoc's default is one tag per controller CLASS ("user-admin-controller"). Those leak the
     * module layout into the published contract and scatter one concept across several groups, so their
     * absence is the thing worth asserting.
     */
    @Test
    void noControllerDerivedTagSurvives() throws Exception {
        assertThat(operationTags(spec()).values().stream().flatMap(List::stream))
                .noneMatch(tag -> tag.toLowerCase().contains("controller"));
    }

    /** A group name is an axis and a resource. Without the axis prefix the flat list stops sorting. */
    @Test
    void everyGroupNamesAnAxisAndThenAResource() throws Exception {
        List<String> declared = declaredGroups(spec());

        assertThat(declared).isNotEmpty().allSatisfy(group -> {
            assertThat(axisOf(group)).as("%s must start with an axis", group).isIn(AXES);
            assertThat(group).as("%s must name a resource after the axis", group)
                    .startsWith(axisOf(group) + OpenApiConfig.AXIS_SEPARATOR)
                    .isNotEqualTo(axisOf(group) + OpenApiConfig.AXIS_SEPARATOR);
        });
    }

    /** Declared order is by axis — operator surface, tenant surface, then what neither owns. */
    @Test
    void everyDeclaredGroupIsDocumentedAndOrderedByAxis() throws Exception {
        JsonNode declared = spec().path("tags");
        List<Integer> axisPositions = new ArrayList<>();
        declared.forEach(tag -> {
            String name = tag.path("name").asText();
            assertThat(tag.path("description").asText()).as("%s needs a description", name).isNotBlank();
            axisPositions.add(AXES.indexOf(axisOf(name)));
        });

        assertThat(axisPositions).isNotEmpty().isSorted();
        assertThat(axisPositions).as("every axis has at least one group")
                .contains(0, 1, 2);
    }

    @Test
    void thePlatformSurfaceIsGroupedOnThePlatformAxis() throws Exception {
        Map<String, List<String>> platform = filterKeys(operationTags(spec()),
                operation -> operation.contains("/api/v1/admin/"));

        assertThat(platform).isNotEmpty().allSatisfy((operation, applied) -> {
            assertThat(applied).as("%s", operation).hasSize(1);
            assertThat(axisOf(applied.getFirst())).as("%s", operation)
                    .isEqualTo(OpenApiConfig.AXIS_PLATFORM);
        });
    }

    @Test
    void theTenantSurfaceIsGroupedOnTheOrganizationAxis() throws Exception {
        // /orgs/{orgId}/... is the tenant surface — except suspend/reactivate, which are platform
        // actions ON a tenant and are gated on platform-admin. That exception is the whole point of
        // deriving from @PreAuthorize rather than from the path.
        Map<String, List<String>> tenant = filterKeys(operationTags(spec()),
                operation -> operation.contains("/api/v1/orgs/{orgId}/")
                        && !operation.contains("/suspend") && !operation.contains("/reactivate"));

        assertThat(tenant).isNotEmpty().allSatisfy((operation, applied) -> {
            assertThat(applied).as("%s", operation).hasSize(1);
            assertThat(axisOf(applied.getFirst())).as("%s", operation)
                    .isEqualTo(OpenApiConfig.AXIS_ORGANIZATION);
        });
    }

    @Test
    void aTenantLifecycleActionStaysWithThePlatformThatPerformsIt() throws Exception {
        Map<String, List<String>> tags = operationTags(spec());

        assertThat(axisOf(groupOf(tags, "POST /api/v1/orgs/{orgId}/suspend")))
                .isEqualTo(OpenApiConfig.AXIS_PLATFORM);
        assertThat(axisOf(groupOf(tags, "POST /api/v1/orgs/{orgId}/reactivate")))
                .isEqualTo(OpenApiConfig.AXIS_PLATFORM);
    }

    /**
     * One controller, both axes, two groups — with nothing in the config listing it as a special case.
     * This is the observable proof that the axis half is still derived from each handler's own
     * {@code @PreAuthorize}: a class-level or path-level scheme could not split a controller at all.
     */
    @Test
    void aControllerWhoseHandlersSpanBothAxesSplitsIntoTwoGroups() throws Exception {
        Map<String, List<String>> tags = operationTags(spec());

        // Both handlers live in OrganizationController; only their @PreAuthorize differs.
        String platformSide = groupOf(tags, "POST /api/v1/orgs");
        String tenantSide = groupOf(tags, "GET /api/v1/orgs/{orgId}");
        assertThat(axisOf(platformSide)).isEqualTo(OpenApiConfig.AXIS_PLATFORM);
        assertThat(axisOf(tenantSide)).isEqualTo(OpenApiConfig.AXIS_ORGANIZATION);
        assertThat(platformSide).isNotEqualTo(tenantSide);
    }

    /** The same resource on both axes must land in different groups — that is the split working. */
    @Test
    void theTwoAuditRoutesLandOnOppositeSidesOfTheAxis() throws Exception {
        Map<String, List<String>> tags = operationTags(spec());

        String platformWide = groupOf(tags, "GET /api/v1/audit");
        String orgScoped = groupOf(tags, "GET /api/v1/orgs/{orgId}/audit");
        assertThat(axisOf(platformWide)).isEqualTo(OpenApiConfig.AXIS_PLATFORM);
        assertThat(axisOf(orgScoped)).isEqualTo(OpenApiConfig.AXIS_ORGANIZATION);
        assertThat(platformWide).isNotEqualTo(orgScoped);
    }

    /** Same path, different method, different authority — so the group has to differ per operation. */
    @Test
    void readingASettingIsSharedWhileWritingOneIsPlatform() throws Exception {
        Map<String, List<String>> tags = operationTags(spec());

        String read = groupOf(tags, "GET /api/v1/settings");
        String write = groupOf(tags, "PUT /api/v1/settings/{key}");
        assertThat(axisOf(read)).isEqualTo(OpenApiConfig.AXIS_SHARED);
        assertThat(axisOf(write)).isEqualTo(OpenApiConfig.AXIS_PLATFORM);
        assertThat(read).isNotEqualTo(write);
    }

    /**
     * With no {@code summary}, Postman falls back to naming the request after the route —
     * "GET /api/v1/orgs/:orgId/webhooks/:id" — and a folder of those is the complaint this whole
     * scheme answers. It comes back the moment one handler ships without an {@code @Operation}, which
     * is why this is a rule and not a list of expected strings: a list gets edited to match whatever
     * the build now produces, so it would pass on the day the naming actually regressed.
     */
    @Test
    void everyOperationIsNamedBySomethingOtherThanItsRoute() throws Exception {
        assertThat(operationSummaries(spec())).isNotEmpty().allSatisfy((operation, summary) -> {
            assertThat(summary).as("%s carries no @Operation(summary = …)", operation).isNotBlank();
            assertThat(summary).as("%s is named after its own route", operation)
                    .doesNotContain("/api/v1");
            assertThat(summary.length()).as("%s is named \"%s\" (%d chars) — move the detail into "
                    + "description rather than raising the budget", operation, summary, summary.length())
                    .isLessThanOrEqualTo(MAX_SUMMARY_LENGTH);
        });
    }

    /**
     * Two requests sharing a name inside one folder are indistinguishable in the list, which reads
     * exactly as badly as no name at all. Scoped per group rather than globally on purpose: the same
     * verb on the same noun in two different folders is unambiguous where it is read.
     */
    @Test
    void noTwoOperationsInOneGroupShareAName() throws Exception {
        JsonNode spec = spec();
        Map<String, String> summaries = operationSummaries(spec);
        Map<String, List<String>> namesByGroup = new LinkedHashMap<>();
        operationTags(spec).forEach((operation, applied) -> applied.forEach(group ->
                namesByGroup.computeIfAbsent(group, name -> new ArrayList<>())
                        .add(summaries.get(operation))));

        assertThat(namesByGroup).isNotEmpty().allSatisfy((group, names) ->
                assertThat(names).as("%s", group).doesNotHaveDuplicates());
    }

    @Test
    void noGroupIsLargeEnoughToNeedScrollingAgain() throws Exception {
        Map<String, Long> sizes = operationTags(spec()).values().stream()
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(tag -> tag, Collectors.counting()));

        assertThat(sizes).isNotEmpty().allSatisfy((group, count) ->
                assertThat(count).as("%s holds %d operations — split the resource label rather than "
                        + "raising the budget", group, count)
                        .isLessThanOrEqualTo(MAX_OPERATIONS_PER_GROUP));
    }
}
