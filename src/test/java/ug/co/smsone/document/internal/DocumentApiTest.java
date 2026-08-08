package ug.co.smsone.document.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The catalog's contract: org scoping via the new document permissions, the personal surface's
 * blast-radius tiering, delete's bytes-now/row-soft asymmetry, and the search tie-in. Storage is
 * mocked at the PORT (the system edge) — the files module's own IT pins the real S3 semantics.
 *
 * <p><strong>{@code document} is one of the seven split tables, and this class exercises both of its
 * homes.</strong> A null {@code org_id} is a PERSONAL document and lives in {@code platform.document};
 * an org's document lives in that tenant's copy (ADR 0010 §2). Neither controller needs a pin — the
 * personal surface names no organization so the request stays on the platform axis, and
 * {@code /api/v1/orgs/{orgId}/documents} is pinned at the edge — but a {@code JdbcTemplate} assertion
 * on the test thread does: unqualified, it resolves against the harness's PLATFORM pin, where an org
 * document is not merely missing, it is in a different table of the same name. So the direct reads
 * about org rows below declare the org's axis, and that is also what makes them assertions about the
 * copy the request actually wrote.
 */
@AutoConfigureMockMvc
class DocumentApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FileStorageProvider storage;

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "content".getBytes());
    }

    @Test
    void orgUploadListDownloadDeleteRoundTrip() throws Exception {
        UUID orgId = seedOrg();
        String manager = seedMember(orgId, "doc-manager", true);
        given(storage.exists(anyString())).willReturn(true); // download's dead-URL guard
        given(storage.presignGet(anyString(), any())).willReturn(URI.create("http://storage.local/signed").toURL());

        MvcResult created = mockMvc.perform(multipart("/api/v1/orgs/{orgId}/documents", orgId)
                        .file(pdf("board report.pdf"))
                        .with(member(orgId, manager)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.name").value("board_report.pdf"))
                .andExpect(jsonPath("$.data.attributes.source").value("UPLOAD"))
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/orgs/{orgId}/documents", orgId).with(member(orgId, manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/documents/{id}", orgId, id)
                        .with(member(orgId, manager)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://storage.local/signed"));

        assertThat(searchRows(id)).as("registered title is searchable").isEqualTo(1);

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/documents/{id}", orgId, id)
                        .with(member(orgId, manager)))
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        then(storage).should().delete(key.capture());
        assertThat(key.getValue()).startsWith("doc/o/" + orgId + "/");
        assertThat(searchRows(id)).as("deleted documents are un-indexed").isZero();
        // Bytes-now, row-soft: invisible to JPA, present for the trail. On the ORG's axis — this
        // document has a non-null org_id, so the soft-deleted row is in the tenant copy of `document`,
        // and the same unqualified name on the harness's platform pin would read the personal one and
        // report zero.
        assertThat(TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from document where id = ?::uuid and deleted_at is not null",
                Integer.class, id))).isEqualTo(1);
        // Same rule, same reason: audit_log is split too, and both of these rows carry this org.
        List<String> actions = TenantContext.callAs(orgId, () -> jdbc.queryForList(
                "select action from audit_log where target = ? order by created_at", String.class, id));
        assertThat(actions).containsExactly("document.registered", "document.deleted");
    }

    @Test
    void anotherOrgsDocumentIsNotFoundNotForbidden() throws Exception {
        UUID orgA = seedOrg();
        UUID orgB = seedOrg();
        String aManager = seedMember(orgA, "a-manager", true);
        String bManager = seedMember(orgB, "b-manager", true);
        MvcResult created = mockMvc.perform(multipart("/api/v1/orgs/{orgId}/documents", orgA)
                        .file(pdf("a.pdf")).with(member(orgA, aManager)))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/orgs/{orgId}/documents/{id}", orgB, id)
                        .with(member(orgB, bManager)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aReaderCannotUploadOrDelete() throws Exception {
        UUID orgId = seedOrg();
        String reader = seedMember(orgId, "doc-reader", false);
        mockMvc.perform(multipart("/api/v1/orgs/{orgId}/documents", orgId)
                        .file(pdf("nope.pdf")).with(member(orgId, reader)))
                .andExpect(status().isForbidden());
        // Both halves of the name: delete is gated at @PreAuthorize, before any lookup.
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/documents/{id}", orgId, UUID.randomUUID())
                        .with(member(orgId, reader)))
                .andExpect(status().isForbidden());
    }

    @Test
    void personalDocumentsTierByBlastRadius() throws Exception {
        given(storage.exists(anyString())).willReturn(true); // download's dead-URL guard
        given(storage.presignGet(anyString(), any())).willReturn(URI.create("http://storage.local/p").toURL());
        // Every caller here is a REAL person: a personal document is owned by one
        // (document.owner_person_id is NOT NULL), and the intruder's 404 only proves the no-oracle rule
        // if it comes from a person who simply isn't the owner — a token that resolves to nobody would
        // be refused a step earlier and prove nothing.
        String owner = seedPerson("owner");
        String intruder = seedPerson("intruder");
        String support = seedPerson("support");
        String admin = seedPerson("admin");
        MvcResult created = mockMvc.perform(multipart("/api/v1/documents")
                        .file(pdf("mine.pdf"))
                        .with(person(owner)))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        // 404, not 403: a foreign personal id must answer exactly like an unknown one (no oracle).
        mockMvc.perform(get("/api/v1/documents/{id}", id).with(person(intruder)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/{id}", id)
                        .with(person(support)
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isFound());
        mockMvc.perform(delete("/api/v1/documents/{id}", id)
                        .with(person(support)
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/documents/{id}", id)
                        .with(person(admin)
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"))))
                .andExpect(status().isNoContent());
    }

    /**
     * A token that resolves to the person behind {@code subject} and to {@code orgId} — both through the
     * link tables. The {@code iss} claim is load-bearing: {@code external_identity} is keyed on
     * (issuer, subject), so a token from another issuer resolves to no person and holds nothing.
     */
    private JwtRequestPostProcessor member(UUID orgId, String subject) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(token -> token.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    /** The same, with no tenant: the personal surface is scoped to a person and to nothing else. */
    private JwtRequestPostProcessor person(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("iss", EdgeSeed.ISSUER));
    }

    /**
     * Unqualified and unpinned: {@code search_document} is platform-tier (the index is derived data,
     * rebuilt rather than extracted — ADR 0010 §2), so it resolves on the harness's own platform pin
     * whichever home the document itself is in.
     */
    private int searchRows(String documentId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from search_document where entity_type = 'document' and entity_id = ?",
                Integer.class, documentId);
        return n == null ? 0 : n;
    }

    /** A tenant: the {@code organization} row whose id IS the tenant key, plus its provider link. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * A person and their Keycloak link; returns the token subject that resolves to them. UUID-suffixed
     * because one Postgres serves the whole suite and {@code external_identity} holds one row per
     * (provider, issuer, subject).
     */
    private String seedPerson(String label) {
        String subject = label + "-" + UUID.randomUUID();
        EdgeSeed.person(jdbc, subject);
        return subject;
    }

    /**
     * That person, plus an ACTIVE membership in {@code orgId} holding the document permissions.
     *
     * <p>Two spans: {@code person} and {@code external_identity} are platform-tier and take the
     * harness's pin, {@code org_role} / {@code role_permission} / {@code membership} are the tenant's
     * (ADR 0010 §2) and take the org's.
     */
    private String seedMember(UUID orgId, String label, boolean manager) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> {
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, ?, 'DocRole', false, 0, now())", roleId, orgId,
                    "DOCS_" + label.toUpperCase().replace('-', '_'));
            jdbc.update("insert into role_permission (role_id, permission) values (?, 'DOCUMENT_READ')", roleId);
            if (manager) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, 'DOCUMENT_MANAGE')",
                        roleId);
            }
            jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                    + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        });
        return subject;
    }
}
