package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * {@code ?include=person} on both member listings — the owner's own report: twenty members on screen
 * meant twenty calls to {@code GET /api/v1/admin/users/{personId}} to put names on them.
 *
 * <p>Four things are asserted and only one of them is about the JSON:
 *
 * <ol>
 *   <li><b>The sideload costs a constant, not a per-member, number of statements.</b> Measured as the
 *       DIFFERENCE between the same page with and without the parameter, so nothing about the rest of
 *       the request has to be counted or predicted — an N+1 behind the compound document would move
 *       that difference to 25 while every shape assertion below still passed.</li>
 *   <li>The {@code included} array carries exactly the people the PAGE names, once each — not the
 *       organization's whole roster, and not a resource twice.</li>
 *   <li>Without the parameter the response is what it always was: no {@code included} key at all.</li>
 *   <li>An include value that names nothing is refused rather than ignored, so a client that ships a
 *       typo learns it instead of concluding the feature does not work.</li>
 * </ol>
 *
 * <p>Both surfaces are exercised because they are two controllers with two authorization models over
 * one roster: {@code platform-support} reading any tenant, and a member holding {@code member:read}
 * reading their own. A sideload that landed on one of them is the seam that gets discovered from
 * Postman a second time.
 */
@AutoConfigureMockMvc
class MemberIncludeApiTest extends AbstractIntegrationTest {

    /** Comfortably above any fixed overhead, and above the default page size, so `?page[size]` is real. */
    private static final int MEMBERS = 25;

    /**
     * One {@code person} statement and one {@code person_contact} statement — the entire cost of the
     * sideload, whatever the page holds. Stated as a ceiling rather than an equality because the
     * comparison is between two whole HTTP requests and the point being defended is the absence of a
     * per-member statement, not the exact constant.
     */
    private static final long SIDELOAD_STATEMENT_BUDGET = 2;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private boolean statisticsWereEnabled;

    private UUID orgId;
    private UUID reader;
    private final List<UUID> people = new ArrayList<>();

    @BeforeEach
    void seedARosterWorthPaginating() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);

        orgId = EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
        // ONE role for the whole roster: every member may read members, so any of them can be the
        // caller on the org-scoped surface and the fixture does not quietly test a 25-role org.
        UUID roleId = TenantContext.callAs(orgId, () -> roles.save(Role.create(orgId, "READER", "Reader",
                false, null, EnumSet.of(Permission.ORG_READ, Permission.MEMBER_READ))).getId());
        people.clear();
        for (int index = 0; index < MEMBERS; index++) {
            people.add(seatAMember(roleId, index));
        }
        reader = people.getFirst();
    }

    @AfterEach
    void restoreStatistics() {
        if (statistics != null) {
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }
    }

    @Test
    void theSideloadCostsAConstantNumberOfStatementsAndNotOnePerMember() throws Exception {
        String path = "/api/v1/admin/orgs/" + orgId + "/members";

        // Warm both shapes. A first request pays for statement metadata, the org-permission cache fill
        // and the route memoization, and charging any of that to one of the two measurements below is
        // how a flat cost reads as a rising one.
        rosterAsSupport(path, MEMBERS, false);
        rosterAsSupport(path, MEMBERS, true);

        long plain = statementsDuring(() -> rosterAsSupport(path, MEMBERS, false));
        long compound = statementsDuring(() -> rosterAsSupport(path, MEMBERS, true));

        assertThat(compound - plain)
                .describedAs("%d sideloaded people must cost a constant number of statements. A "
                        + "difference of ~%d means the compound document is being assembled by asking "
                        + "for one person at a time — the client's N+1 moved to the server, where the "
                        + "response shape still looks correct.", MEMBERS, MEMBERS)
                .isLessThanOrEqualTo(SIDELOAD_STATEMENT_BUDGET);
    }

    @Test
    void theIncludedArrayCarriesExactlyThePeopleThisPageNamesOnceEach() throws Exception {
        // Two of twenty-five, so "exactly this page" is falsifiable: a sideload resolved from the org
        // rather than from the window would answer 25 here and every other assertion would pass.
        String body = mockMvc.perform(get("/api/v1/admin/orgs/{orgId}/members", orgId)
                        .param("page[size]", "2").param("include", "person").with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        List<String> onThePage = JsonPath.read(body, "$.data[*].attributes.personId");
        List<String> included = JsonPath.read(body, "$.included[*].id");
        List<String> types = JsonPath.read(body, "$.included[*].type");

        assertThat(included).containsExactlyInAnyOrderElementsOf(onThePage);
        assertThat(included).doesNotHaveDuplicates();
        assertThat(types).containsOnly("user");
        // Same id and same type as GET /api/v1/admin/users/{personId}, so a client merges rather than
        // keeping two kinds of person.
        assertThat(JsonPath.<List<String>>read(body, "$.included[*].attributes.name.formattedName"))
                .allMatch(name -> name.startsWith("Member "));
        assertThat(JsonPath.<List<String>>read(body, "$.included[*].attributes.email"))
                .allMatch(email -> email.endsWith("@include.test"));
    }

    @Test
    void aMemberWhosePersonWasErasedKeepsTheirRowAndSimplyHasNoEntry() throws Exception {
        UUID erased = people.get(1);
        jdbc.update("update person set deleted_at = now() where id = ?", erased);

        String body = mockMvc.perform(get("/api/v1/admin/orgs/{orgId}/members", orgId)
                        .param("page[size]", String.valueOf(MEMBERS)).param("include", "person")
                        .with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(MEMBERS))
                .andReturn().getResponse().getContentAsString();

        List<String> included = JsonPath.read(body, "$.included[*].id");
        assertThat(included)
                .describedAs("the membership row survives the human — the roster shows it with a bare "
                        + "id rather than inventing a person to explain the gap")
                .hasSize(MEMBERS - 1)
                .doesNotContain(erased.toString());
    }

    @Test
    void theOrgScopedRosterSideloadsUnderTheSameMemberReadItAlreadyNeeded() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId)
                        .param("page[size]", "3").param("include", "person")
                        .with(token(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.included.length()").value(3))
                .andExpect(jsonPath("$.included[0].type").value("user"))
                .andExpect(jsonPath("$.included[0].attributes.email").exists())
                .andExpect(jsonPath("$.included[0].attributes.name.givenName").value("Member"));
    }

    @Test
    void withoutTheParameterTheResponseIsUnchanged() throws Exception {
        // Byte-compatibility with every client that has never heard of this feature is the whole reason
        // the sideload is opt-in: `included` must be ABSENT, not an empty array.
        for (String path : List.of("/api/v1/admin/orgs/" + orgId + "/members",
                "/api/v1/orgs/" + orgId + "/members")) {
            RequestPostProcessor caller = path.startsWith("/api/v1/admin/") ? support() : token(reader);
            String body = mockMvc.perform(get(path).param("page[size]", "3").with(caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.included").doesNotExist())
                    .andExpect(jsonPath("$.meta.requestId").exists())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("\"included\"");
        }
    }

    @Test
    void anIncludeThatNamesNothingIsRefusedRatherThanIgnored() throws Exception {
        // 422 with source.parameter, the same refusal shape `status` and `page[after]` already use on
        // this surface. Ignoring it would leave a client with a typo concluding the sideload is broken
        // and going back to one request per member.
        mockMvc.perform(get("/api/v1/admin/orgs/{orgId}/members", orgId)
                        .param("include", "persons").with(support()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("include"));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId)
                        .param("include", "person,role").with(token(reader)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("include"));
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * One roster request, asserting that it actually did the thing being measured.
     *
     * <p>The {@code included} assertion is not decoration: without it a sideload that quietly resolved
     * NOTHING would cost zero extra statements and sail through the budget above — the measurement
     * would be proving that a broken feature is fast.
     */
    private void rosterAsSupport(String path, int size, boolean withPeople) {
        try {
            var request = get(path).param("page[size]", String.valueOf(size)).with(support());
            if (withPeople) {
                request = request.param("include", "person");
            }
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(size))
                    .andExpect(withPeople
                            ? jsonPath("$.included.length()").value(size)
                            : jsonPath("$.included").doesNotExist());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long statementsDuring(Runnable work) {
        long before = statistics.getPrepareStatementCount();
        work.run();
        return statistics.getPrepareStatementCount() - before;
    }

    /**
     * A person with the three projected name components, a verified address, and a seat in this org.
     *
     * <p>{@code org_role} and {@code membership} are tenant-tier so the seat is written on the
     * organization's own axis; {@code person}, {@code person_contact} and {@code external_identity}
     * are platform-tier and go on the harness's PLATFORM pin (ADR 0010 §2).
     */
    private UUID seatAMember(UUID roleId, int index) {
        UUID personId = EdgeSeed.personWithEmail(jdbc, "kc-" + UUID.randomUUID(),
                UUID.randomUUID() + "@include.test");
        jdbc.update("update person set formatted_name = ?, given_name = 'Member', family_name = ? "
                + "where id = ?", "Member " + index, String.valueOf(index), personId);
        TenantContext.runAs(orgId,
                () -> memberships.save(Membership.create(orgId, personId, roleId, "READER")));
        return personId;
    }

    /**
     * A platform-support caller. <b>No {@code iss} claim, deliberately</b>: without one the edge never
     * consults {@code external_identity}, so this token resolves to no person and costs no statement —
     * which keeps the two measurements above comparing the sideload and nothing else. The authority is
     * what the surface actually checks.
     */
    private static RequestPostProcessor support() {
        return jwt().jwt(token -> token.subject("support-member-include"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"));
    }

    /**
     * A token the edge resolves to this person in this tenant — issuer, the subject they were LINKED
     * by, and an alias-keyed {@code organization} claim carrying the PROVIDER's org id, all read back
     * from the rows {@link EdgeSeed} wrote. Matching the local slug instead would be the tenant
     * crossing V11 exists to prevent.
     */
    private JwtRequestPostProcessor token(UUID personId) {
        String subject = jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ?", String.class, personId);
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(token -> token.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }
}
