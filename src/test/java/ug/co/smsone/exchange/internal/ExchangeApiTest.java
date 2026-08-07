package ug.co.smsone.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The REST contract: 202-submit with the handler-permission gate, polled progress, the
 * row-addressed error report behind a 302, the export result round trip, artifact access limited
 * to the requester or holders of the handler's permission, and the discoverable handler catalog.
 */
@AutoConfigureMockMvc
@Import(ExchangeTestSupport.class)
class ExchangeApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExchangeWorker worker;

    @Autowired
    private ExchangeTestSupport.InMemoryFileStorage storage;

    @Autowired
    private ExchangeTestSupport.CountingExchangeHandler handler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private double counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @BeforeEach
    void reset() {
        handler.reset();
        // The queue is one shared table and drainOnce() claims strictly oldest-first — leftovers
        // from other tests would be claimed instead of this test's job.
        jdbc.update("delete from exchange_job");
    }

    @Test
    void submittingWithoutTheHandlersPermissionIsRefused() throws Exception {
        UUID orgId = seedOrg();
        // org-members imports need member:invite
        String reader = seedMember(orgId, "plain-reader", "EXCHANGE_READ", "EXCHANGE_SUBMIT");

        mockMvc.perform(multipart("/api/v1/orgs/{orgId}/exchange/imports", orgId)
                        .file(csv("members.csv", "email,givenName,familyName,roleCode\n"))
                        .param("handler", "org-members")
                        .with(member(orgId, reader)))
                .andExpect(status().isForbidden());
    }

    @Test
    void importLifecycleReportsInvalidRowsByNumberAndFilesItsArtifacts() throws Exception {
        double jobsBefore = counter("smsone.exchange.jobs",
                "handler", "test-counter", "outcome", "completed_with_errors");
        double processedBefore = counter("smsone.exchange.records",
                "handler", "test-counter", "result", "processed");
        double failedBefore = counter("smsone.exchange.records",
                "handler", "test-counter", "result", "failed");
        UUID orgId = seedOrg();
        String importer = seedMember(orgId, "importer-1",
                "ORG_READ", "MEMBER_READ", "EXCHANGE_READ", "EXCHANGE_SUBMIT");
        String source = "key,value\nk1,v1\n,v2\nk3,bad\nk4,v4\n"; // rows 2 and 3 are data errors

        MvcResult accepted = mockMvc.perform(multipart("/api/v1/orgs/{orgId}/exchange/imports", orgId)
                        .file(csv("data.csv", source))
                        .param("handler", ExchangeTestSupport.CountingExchangeHandler.ID)
                        .param("format", "csv")
                        .with(member(orgId, importer)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.type").value("exchange-job"))
                .andExpect(jsonPath("$.data.attributes.status").value("PENDING"))
                .andReturn();
        String jobId = JsonPath.read(accepted.getResponse().getContentAsString(), "$.data.id");

        assertThat(worker.drainOnce()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/orgs/{orgId}/exchange/jobs/{id}", orgId, jobId)
                        .with(member(orgId, importer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.attributes.processed").value(2))
                .andExpect(jsonPath("$.data.attributes.failed").value(2));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/exchange/jobs", orgId)
                        .with(member(orgId, importer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(jobId));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/exchange/jobs/{id}/report", orgId, jobId)
                        .with(member(orgId, importer)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("http://storage.local/signed/")));

        String report = new String(storage.objects.get("exch/o/" + orgId + "/" + jobId + "/errors.csv"),
                StandardCharsets.UTF_8);
        assertThat(report).contains("row_number,error")
                .contains("2,key is required.")
                .contains("3,value 'bad' is not allowed.");

        // Both artifacts — the source and the report — are filed as EXCHANGE documents.
        assertThat(jdbc.queryForObject(
                "select count(*) from document where org_id = ? and source = 'EXCHANGE'",
                Integer.class, orgId)).isEqualTo(2);

        // Throughput is counted, per outcome and per record result.
        assertThat(counter("smsone.exchange.jobs",
                "handler", "test-counter", "outcome", "completed_with_errors")).isEqualTo(jobsBefore + 1);
        assertThat(counter("smsone.exchange.records",
                "handler", "test-counter", "result", "processed")).isEqualTo(processedBefore + 2);
        assertThat(counter("smsone.exchange.records",
                "handler", "test-counter", "result", "failed")).isEqualTo(failedBefore + 2);
    }

    @Test
    void exportRoundTripsAndItsResultIsGatedOnTheHandlersPermission() throws Exception {
        UUID orgId = seedOrg();
        String exporter = seedMember(orgId, "exporter-1",
                "ORG_READ", "MEMBER_READ", "EXCHANGE_READ", "EXCHANGE_SUBMIT");
        // reaches job metadata but NOT member:read
        String bystander = seedMember(orgId, "bystander", "EXCHANGE_READ");

        MvcResult accepted = mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/exports", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\": \"test-counter\", \"format\": \"CSV\"}")
                        .with(member(orgId, exporter)))
                .andExpect(status().isAccepted())
                .andReturn();
        String jobId = JsonPath.read(accepted.getResponse().getContentAsString(), "$.data.id");

        assertThat(worker.drainOnce()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/orgs/{orgId}/exchange/jobs/{id}", orgId, jobId)
                        .with(member(orgId, exporter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.attributes.processed").value(3));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/exchange/jobs/{id}/result", orgId, jobId)
                        .with(member(orgId, exporter)))
                .andExpect(status().isFound());
        String result = new String(
                storage.objects.get("exch/o/" + orgId + "/" + jobId + "/test-counter-export.csv"),
                StandardCharsets.UTF_8);
        assertThat(result).contains("key,value").contains("k1,v1").contains("k3,v3");

        // Job METADATA is org-readable; the artifact carries the data and stays gated.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/exchange/jobs/{id}", orgId, jobId)
                        .with(member(orgId, bystander)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/exchange/jobs/{id}/result", orgId, jobId)
                        .with(member(orgId, bystander)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/jobs/{id}/cancel", orgId, jobId)
                        .with(member(orgId, exporter)))
                .andExpect(status().isConflict());
    }

    @Test
    void anotherOrgsJobIsNotFoundNotForbidden() throws Exception {
        UUID orgA = seedOrg();
        UUID orgB = seedOrg();
        String aMember = seedMember(orgA, "a-member",
                "ORG_READ", "MEMBER_READ", "EXCHANGE_READ", "EXCHANGE_SUBMIT");
        String bMember = seedMember(orgB, "b-member", "EXCHANGE_READ");

        MvcResult accepted = mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/exports", orgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\": \"test-counter\", \"format\": \"CSV\"}")
                        .with(member(orgA, aMember)))
                .andExpect(status().isAccepted())
                .andReturn();
        String jobId = JsonPath.read(accepted.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/orgs/{orgId}/exchange/jobs/{id}", orgB, jobId)
                        .with(member(orgB, bMember)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theHandlerCatalogIsDiscoverableToAnyAuthenticatedUser() throws Exception {
        MvcResult catalog = mockMvc.perform(get("/api/v1/exchange/handlers")
                        .with(jwt().jwt(token -> token.subject("anyone"))))
                .andExpect(status().isOk())
                .andReturn();
        String body = catalog.getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data[*].id");
        assertThat(ids).contains("org-members", "test-counter");
        List<String> memberHeader = JsonPath.read(body,
                "$.data[?(@.id=='org-members')].attributes.header[*]");
        // givenName/familyName, not first/last: the person model's name parts are given/family now and
        // MembersExchangeHandler.header() publishes them verbatim. The catalog IS that wire contract, so
        // the assertion follows the columns rather than the other way round.
        assertThat(memberHeader).containsExactly("email", "givenName", "familyName", "roleCode");
        List<String> permissions = JsonPath.read(body,
                "$.data[?(@.id=='org-members')].attributes.importPermission");
        assertThat(permissions).containsExactly("member:invite");
    }

    private static MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A tenant the edge can resolve: the {@code organization} row whose id IS the tenant key, plus the
     * provider link a token's {@code organization} claim names.
     */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * A token that resolves to the person behind {@code subject} AND to {@code orgId} — both through the
     * link tables, which is the only way either resolves now. The {@code iss} claim is not decoration:
     * {@code external_identity} is keyed on (issuer, subject), so a token from another issuer resolves to
     * no person and holds no permission in any org.
     */
    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", orgClaim(orgId)));
    }

    /** Keycloak's alias-keyed {@code organization} claim, rebuilt from the seeded provider link. */
    private Map<String, Object> orgClaim(UUID orgId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return Map.of(String.valueOf(link.get("external_alias")),
                Map.of("id", String.valueOf(link.get("external_org_id"))));
    }

    /**
     * A person with an ACTIVE membership in {@code orgId} carrying {@code permissions}; returns the
     * token subject that resolves to them. The subject is UUID-suffixed because one Postgres serves the
     * whole suite and {@code external_identity} holds one row per (provider, issuer, subject).
     */
    private String seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'ExchangeRole', false, 0, now())", roleId, orgId,
                "XCH_" + label.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        return subject;
    }
}
