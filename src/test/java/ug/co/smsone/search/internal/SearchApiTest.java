package ug.co.smsone.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ug.co.smsone.identity.PersonProvisioned;
import ug.co.smsone.search.SearchDoc;
import ug.co.smsone.search.SearchIndex;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The query surface's contract: tenant isolation cut inside the SQL, FTS-then-trigram strategy
 * carried by the cursor, platform-wide rows invisible to tenant search. Complemented by
 * {@code SearchPerformanceTest}, which pins the speed claim with a measured number.
 */
@AutoConfigureMockMvc
class SearchApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SearchIndex index;

    @Autowired
    private SearchEventListeners listeners;

    @Autowired
    private JdbcTemplate jdbc;

    private MockHttpServletRequestBuilder orgSearch(UUID orgId, String subject, String query) {
        return get("/api/v1/orgs/{orgId}/search?q={q}", orgId, query).with(searcher(orgId, subject));
    }

    /**
     * The token a seeded searcher authenticates with. Both halves resolve through a link table now: the
     * subject through {@code external_identity} (keyed on issuer AND subject, so {@code iss} is
     * load-bearing), the alias-keyed {@code organization} claim through {@code external_organization}.
     */
    private org.springframework.test.web.servlet.request.RequestPostProcessor searcher(UUID orgId, String subject) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(token -> token.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    @Test
    void tenantSearchFindsOwnDocumentsAndNeverAnotherOrgs() throws Exception {
        String subject = "searcher-" + UUID.randomUUID();
        UUID orgA = seedOrgRead(subject);
        // Never seeded as a tenant on purpose: nobody authenticates into it, and the isolation cut
        // being proved is the one inside the SQL, against the index's org_id.
        UUID orgB = UUID.randomUUID();
        index.upsert(new SearchDoc(orgA, "probe", "a-1", "Quarterly revenue report", "numbers for the board"));
        index.upsert(new SearchDoc(orgB, "probe", "b-1", "Quarterly revenue report", "numbers for the board"));

        mockMvc.perform(orgSearch(orgA, subject, "quarterly revenue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.entityId").value("a-1"))
                .andExpect(jsonPath("$.data[0].attributes.snippet").exists())
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void platformWideRowsAreInvisibleToTenantSearchButFoundByAdminSearch() throws Exception {
        String subject = "searcher-" + UUID.randomUUID();
        UUID orgA = seedOrgRead(subject);
        String email = "finder-" + UUID.randomUUID() + "@smsone.co.ug";
        index.upsert(new SearchDoc(null, "user", "sub-" + UUID.randomUUID(), email, email));

        mockMvc.perform(orgSearch(orgA, subject, email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/v1/admin/search?q={q}", email)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.title").value(email));
    }

    @Test
    void aPrefixThatMatchesNoTokenFallsBackToTrigramAndTheCursorKeepsTheMode() throws Exception {
        String subject = "searcher-" + UUID.randomUUID();
        UUID orgId = seedOrgRead(subject);
        index.upsert(new SearchDoc(orgId, "probe", "t-1", "Reconciliation Handbook", "settlement steps"));
        index.upsert(new SearchDoc(orgId, "probe", "t-2", "Reconciliation Manual", "settlement steps"));
        index.upsert(new SearchDoc(orgId, "probe", "t-3", "Reconciliation Guide", "settlement steps"));

        MvcResult first = mockMvc.perform(get("/api/v1/orgs/{orgId}/search?q={q}&page[size]=2", orgId, "reconcil")
                        .with(searcher(orgId, subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.page.hasMore").value(true))
                .andReturn();

        String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.meta.page.nextCursor");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/search", orgId)
                        .param("q", "reconcil")
                        .param("page[size]", "2")
                        .param("page[after]", cursor)
                        .with(searcher(orgId, subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.page.hasMore").value(false));
    }

    @Test
    void aBlankQueryIsA422NamingTheParameter() throws Exception {
        String subject = "searcher-" + UUID.randomUUID();
        UUID orgId = seedOrgRead(subject);
        mockMvc.perform(orgSearch(orgId, subject, " "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("q"));
    }

    @Test
    void anEventRedeliveryDoesNotDuplicateTheDocument() {
        UUID personId = UUID.randomUUID();
        PersonProvisioned event = new PersonProvisioned(personId, personId + "@smsone.co.ug", Instant.now());
        // @ApplicationModuleListener is async even when invoked directly (the proxy dispatches) —
        // fire the delivery and its redelivery, then await the settled outcome.
        listeners.on(event);
        listeners.on(event);

        // Both counts declare the platform axis: the listener under test is asynchronous, so the only
        // way to assert on it is from Awaitility's own poll thread — which carries no axis, and would
        // route these reads to the empty no_tenant schema (ADR 0010 §3.4). Both tables are
        // platform-tier: search_document has a nullable org_id (person docs have none) and event_inbox
        // is infrastructure.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer docs = TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                    "select count(*) from search_document where entity_type = 'user' and entity_id = ?",
                    Integer.class, personId.toString()));
            assertThat(docs).as("one document, however many deliveries").isEqualTo(1);
            // The inbox key is namespaced by what the event now identifies: a person id, not a token
            // subject. The projection's entity_type stays 'user' (the API's word for a human with an
            // account) — these are two different vocabularies and only the key moved.
            Integer inboxRows = TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                    "select count(*) from event_inbox where listener_id = 'search' and message_id = ?",
                    Integer.class, "person:" + personId + "@" + event.occurredAt()));
            assertThat(inboxRows).as("the inbox recorded the message exactly once").isEqualTo(1);
        });
    }

    /**
     * A tenant the edge can resolve, the person {@code subject} authenticates as, and their membership in
     * a role holding search:query. Returns {@code organization.id} — the tenant key the URL carries and
     * the SQL cuts on. Org and person come from {@link EdgeSeed} because each needs its provider link as
     * much as its row; the role and membership go straight into the tables, since search's own concern is
     * the projection rather than how a role gets built.
     */
    private UUID seedOrgRead(String subject) {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, 'OWNER', 'Owner', true, 0, now())", roleId, orgId);
        // The column stores the enum NAME (SEARCH_QUERY), not the wire code (search:query) — see DATA_MODEL §4.4.3.
        jdbc.update("insert into role_permission (role_id, permission) values (?, 'SEARCH_QUERY')", roleId);
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())",
                UUID.randomUUID(), orgId, personId, roleId);
        return orgId;
    }
}
