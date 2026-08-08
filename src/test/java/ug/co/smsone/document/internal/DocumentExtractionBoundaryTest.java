package ug.co.smsone.document.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * <b>What a document takes with it when its organization is extracted, and what it must leave behind
 * (ADR 0010 §2 row 6, §2.2, §6).</b> Two halves of one boundary, and both have to hold or the bundle is
 * wrong: the KEY says whose bytes these are (extraction copies an object PREFIX), and the ROW's schema
 * says whose record it is ({@code pg_dump -n t_<hex>} copies a schema WHOLE).
 *
 * <p>Its sibling {@code shared.document.StorageKeyNamespaceTest} pins the shape of a key without a
 * container — the guard in {@code NewDocument} and the enumeration of everything that writes bytes.
 * This class pins the keys the live minters actually produce and the home the matching row lands in,
 * which no static check can see because the home is chosen by the {@code search_path} at the moment
 * the connection is borrowed.
 *
 * <p><b>The personal cases are the ones that were broken.</b> {@code CurrentUserFilter} pins the
 * caller's organization whenever their token names exactly one — whatever the route — and
 * {@code /api/v1/documents} is not under {@code /api/v1/orgs/{orgId}/}, so a single-org member's
 * personal upload used to land in that tenant's schema carrying a null {@code org_id}. It would then
 * have left with the tenant at extraction, been stranded in {@code tenant_pool} at promotion, and
 * disappeared from its owner the day they joined a second organization. Storage is mocked at the PORT
 * (the system edge) exactly as {@code DocumentApiTest} does it; the keys are what this class reads off
 * it.
 */
@AutoConfigureMockMvc
class DocumentExtractionBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FileStorageProvider storage;

    /**
     * The document half of ADR 0010 §6's extraction bundle, item 7. The other half is
     * {@code exch/o/<orgId>/}, which is not driven over HTTP here — an exchange submit needs a handler,
     * an entitlement and two permissions — but every exchange artifact is filed through
     * {@code NewDocument} on its way to the object store, so the same rule holds it and
     * {@code StorageKeyNamespaceTest} states its shape.
     */
    private static final String ORG_DOCUMENT_PREFIX = "doc/o/";

    @Test
    void anOrganizationsDocumentIsKeyedUnderThatOrganizationAndFiledInItsSchema() throws Exception {
        UUID orgId = seedOrg();
        Member manager = seedMember(orgId, true);

        MvcResult created = mockMvc.perform(multipart("/api/v1/orgs/{orgId}/documents", orgId)
                        .file(pdf("board report.pdf"))
                        .with(tokenNaming(orgId, manager.subject())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.data.id"));

        assertThat(capturedKey())
                .as("ADR 0010 §6 bundle item 7 copies the object store by PREFIX, so a key that does not"
                        + " name its org is a byte no extraction can find")
                .startsWith(ORG_DOCUMENT_PREFIX + orgId + "/");
        assertThat(rowsIn(orgId, id)).as("the org's own copy of the split table is where its record lives")
                .isEqualTo(1);
        assertThat(rowsInPlatform(id)).as("and it is NOT in the platform copy — that one holds the rows a"
                + " tenant must never carry away").isZero();
    }

    /**
     * The regression. The caller is the ordinary case — a human seated in exactly one organization, which
     * is every one of the 193,940 people in the seeded database — so their token names that org and the
     * edge pins it. The personal upload must still land on the platform axis.
     */
    @Test
    void aPersonalDocumentUploadedByASingleOrgMemberIsFiledOutsideThatTenantsSchema() throws Exception {
        UUID orgId = seedOrg();
        Member member = seedMember(orgId, false);

        MvcResult created = mockMvc.perform(multipart("/api/v1/documents")
                        .file(pdf("payslip.pdf"))
                        .with(tokenNaming(orgId, member.subject())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.orgId").doesNotExist())
                .andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.data.id"));

        assertThat(capturedKey())
                .as("the key names the human, never the organization they happen to be seated in")
                .startsWith("doc/u/" + member.personId() + "/")
                .doesNotContain("/o/");
        assertThat(rowsInPlatform(id))
                .as("a personal document belongs to a human (ADR 0010 §2.2) and lives in the platform copy")
                .isEqualTo(1);
        assertThat(rowsIn(orgId, id))
                .as("and NOT in the tenant's schema. pg_dump -n takes a schema whole, so a row that sat"
                        + " here would leave with an organization that never owned it — and its bytes"
                        + " would not follow, since doc/u/<personId>/ is in neither of the bundle's"
                        + " object prefixes")
                .isZero();
    }

    /**
     * The same bug seen from the owner's side, and the reason it would have been reported as data loss
     * rather than as a leak. A person in two organizations resolves to NO organization (ADR 0010 §3.3),
     * so their request lands on the platform axis — which has to be the same axis their first upload
     * used, or the document they filed last week is simply gone.
     */
    @Test
    void aPersonalDocumentIsStillReachableAfterItsOwnerStopsResolvingToOneOrganization() throws Exception {
        UUID orgId = seedOrg();
        Member member = seedMember(orgId, false);
        given(storage.exists(anyString())).willReturn(true);
        given(storage.presignGet(anyString(), any())).willReturn(URI.create("http://storage.local/p").toURL());

        MvcResult created = mockMvc.perform(multipart("/api/v1/documents")
                        .file(pdf("payslip.pdf"))
                        .with(tokenNaming(orgId, member.subject())))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/documents/{id}", id).with(tokenNamingNoOrg(member.subject())))
                .andExpect(status().isFound());
        mockMvc.perform(get("/api/v1/documents").with(tokenNamingNoOrg(member.subject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "content".getBytes());
    }

    /** The key the request handed to the storage port — the thing an extraction would have to find. */
    private String capturedKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        then(storage).should().put(key.capture(), any(), anyLong(), anyString());
        return key.getValue();
    }

    /** Rows with this id in the ORG's copy of the split table, whichever schema that org is placed in. */
    private int rowsIn(UUID orgId, UUID id) {
        Integer n = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from document where id = ?", Integer.class, id));
        return n == null ? 0 : n;
    }

    /** Rows with this id in the platform copy. Named rather than pinned, so the axis cannot flatter it. */
    private int rowsInPlatform(UUID id) {
        Integer n = jdbc.queryForObject("select count(*) from platform.document where id = ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }

    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    private record Member(String subject, UUID personId) {
    }

    /**
     * A person with an ACTIVE seat in {@code orgId}. {@code manager} adds the two document permissions —
     * the personal surface needs neither, which is itself part of the point: it is reachable by any
     * signed-in human, and nothing about it announces that it is writing into a split table.
     */
    private Member seedMember(UUID orgId, boolean manager) {
        String subject = "doc-boundary-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = EdgeSeed.member(jdbc, orgId, personId, "DOCS_" + UUID.randomUUID().toString().substring(0, 8));
        if (manager) {
            TenantContext.runAs(orgId, () -> {
                jdbc.update("insert into role_permission (role_id, permission) values (?, 'DOCUMENT_READ')",
                        roleId);
                jdbc.update("insert into role_permission (role_id, permission) values (?, 'DOCUMENT_MANAGE')",
                        roleId);
            });
        }
        return new Member(subject, personId);
    }

    /** A token that resolves to this person AND to this organization — the single-org human's shape. */
    private JwtRequestPostProcessor tokenNaming(UUID orgId, String subject) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from platform.external_organization"
                        + " where organization_id = ?", orgId);
        return jwt().jwt(token -> token.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    /** The same person with no organization claim — what a multi-org human's token resolves to. */
    private JwtRequestPostProcessor tokenNamingNoOrg(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("iss", EdgeSeed.ISSUER));
    }
}
