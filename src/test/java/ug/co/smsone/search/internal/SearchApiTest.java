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
import ug.co.smsone.testsupport.AbstractIntegrationTest;

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

    private MockHttpServletRequestBuilder orgSearch(UUID orgId, String query) {
        return get("/api/v1/orgs/{orgId}/search?q={q}", orgId, query)
                .with(jwt().jwt(token -> token.subject("searcher")
                        .claim("organization", Map.of("acme", Map.of("id", orgId.toString())))));
    }

    @Test
    void tenantSearchFindsOwnDocumentsAndNeverAnotherOrgs() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        seedOrgRead(orgA, "searcher");
        index.upsert(new SearchDoc(orgA, "probe", "a-1", "Quarterly revenue report", "numbers for the board"));
        index.upsert(new SearchDoc(orgB, "probe", "b-1", "Quarterly revenue report", "numbers for the board"));

        mockMvc.perform(orgSearch(orgA, "quarterly revenue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.entityId").value("a-1"))
                .andExpect(jsonPath("$.data[0].attributes.snippet").exists())
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void platformWideRowsAreInvisibleToTenantSearchButFoundByAdminSearch() throws Exception {
        UUID orgA = UUID.randomUUID();
        seedOrgRead(orgA, "searcher");
        String email = "finder-" + UUID.randomUUID() + "@smsone.co.ug";
        index.upsert(new SearchDoc(null, "user", "sub-" + UUID.randomUUID(), email, email));

        mockMvc.perform(orgSearch(orgA, email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/v1/admin/search?q={q}", email)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.title").value(email));
    }

    @Test
    void aPrefixThatMatchesNoTokenFallsBackToTrigramAndTheCursorKeepsTheMode() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedOrgRead(orgId, "searcher");
        index.upsert(new SearchDoc(orgId, "probe", "t-1", "Reconciliation Handbook", "settlement steps"));
        index.upsert(new SearchDoc(orgId, "probe", "t-2", "Reconciliation Manual", "settlement steps"));
        index.upsert(new SearchDoc(orgId, "probe", "t-3", "Reconciliation Guide", "settlement steps"));

        MvcResult first = mockMvc.perform(get("/api/v1/orgs/{orgId}/search?q={q}&page[size]=2", orgId, "reconcil")
                        .with(jwt().jwt(token -> token.subject("searcher")
                                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.page.hasMore").value(true))
                .andReturn();

        String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.meta.page.nextCursor");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/search", orgId)
                        .param("q", "reconcil")
                        .param("page[size]", "2")
                        .param("page[after]", cursor)
                        .with(jwt().jwt(token -> token.subject("searcher")
                                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.page.hasMore").value(false));
    }

    @Test
    void aBlankQueryIsA422NamingTheParameter() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedOrgRead(orgId, "searcher");
        mockMvc.perform(orgSearch(orgId, " "))
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

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer docs = jdbc.queryForObject(
                    "select count(*) from search_document where entity_type = 'user' and entity_id = ?",
                    Integer.class, personId.toString());
            assertThat(docs).as("one document, however many deliveries").isEqualTo(1);
            Integer inboxRows = jdbc.queryForObject(
                    "select count(*) from event_inbox where listener_id = 'search' and message_id = ?",
                    Integer.class, "user:" + personId + "@" + event.occurredAt());
            assertThat(inboxRows).as("the inbox recorded the message exactly once").isEqualTo(1);
        });
    }

    /** Org + membership + role with search:query, straight into the tables — search's own concern is the projection. */
    private void seedOrgRead(UUID orgId, String subject) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 8), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, 'OWNER', 'Owner', true, 0, now())", roleId, orgId);
        // The column stores the enum NAME (SEARCH_QUERY), not the wire code (search:query) — see DATA_MODEL §4.4.3.
        jdbc.update("insert into role_permission (role_id, permission) values (?, 'SEARCH_QUERY')", roleId);
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())",
                UUID.randomUUID(), orgId, subject, roleId);
    }
}
