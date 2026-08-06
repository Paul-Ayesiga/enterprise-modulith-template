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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The catalog's contract: org scoping via the new document permissions, the personal surface's
 * blast-radius tiering, delete's bytes-now/row-soft asymmetry, and the search tie-in. Storage is
 * mocked at the PORT (the system edge) — the files module's own IT pins the real S3 semantics.
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
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "doc-manager", true);
        given(storage.exists(anyString())).willReturn(true); // download's dead-URL guard
        given(storage.presignGet(anyString(), any())).willReturn(URI.create("http://storage.local/signed").toURL());

        MvcResult created = mockMvc.perform(multipart("/api/v1/orgs/{orgId}/documents", orgId)
                        .file(pdf("board report.pdf"))
                        .with(member(orgId, "doc-manager")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.name").value("board_report.pdf"))
                .andExpect(jsonPath("$.data.attributes.source").value("UPLOAD"))
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/orgs/{orgId}/documents", orgId).with(member(orgId, "doc-manager")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/documents/{id}", orgId, id)
                        .with(member(orgId, "doc-manager")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://storage.local/signed"));

        assertThat(searchRows(id)).as("registered title is searchable").isEqualTo(1);

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/documents/{id}", orgId, id)
                        .with(member(orgId, "doc-manager")))
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        then(storage).should().delete(key.capture());
        assertThat(key.getValue()).startsWith("doc/o/" + orgId + "/");
        assertThat(searchRows(id)).as("deleted documents are un-indexed").isZero();
        // Bytes-now, row-soft: invisible to JPA, present for the trail.
        assertThat(jdbc.queryForObject(
                "select count(*) from document where id = ?::uuid and deleted_at is not null",
                Integer.class, id)).isEqualTo(1);
        List<String> actions = jdbc.queryForList(
                "select action from audit_log where target = ? order by created_at", String.class, id);
        assertThat(actions).containsExactly("document.registered", "document.deleted");
    }

    @Test
    void anotherOrgsDocumentIsNotFoundNotForbidden() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        seedMember(orgA, "a-manager", true);
        seedMember(orgB, "b-manager", true);
        MvcResult created = mockMvc.perform(multipart("/api/v1/orgs/{orgId}/documents", orgA)
                        .file(pdf("a.pdf")).with(member(orgA, "a-manager")))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/orgs/{orgId}/documents/{id}", orgB, id)
                        .with(member(orgB, "b-manager")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aReaderCannotUploadOrDelete() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "doc-reader", false);
        mockMvc.perform(multipart("/api/v1/orgs/{orgId}/documents", orgId)
                        .file(pdf("nope.pdf")).with(member(orgId, "doc-reader")))
                .andExpect(status().isForbidden());
        // Both halves of the name: delete is gated at @PreAuthorize, before any lookup.
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/documents/{id}", orgId, UUID.randomUUID())
                        .with(member(orgId, "doc-reader")))
                .andExpect(status().isForbidden());
    }

    @Test
    void personalDocumentsTierByBlastRadius() throws Exception {
        given(storage.exists(anyString())).willReturn(true); // download's dead-URL guard
        given(storage.presignGet(anyString(), any())).willReturn(URI.create("http://storage.local/p").toURL());
        MvcResult created = mockMvc.perform(multipart("/api/v1/documents")
                        .file(pdf("mine.pdf"))
                        .with(jwt().jwt(token -> token.subject("owner-1"))))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        // 404, not 403: a foreign personal id must answer exactly like an unknown one (no oracle).
        mockMvc.perform(get("/api/v1/documents/{id}", id).with(jwt().jwt(t -> t.subject("intruder"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/{id}", id)
                        .with(jwt().jwt(t -> t.subject("support-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isFound());
        mockMvc.perform(delete("/api/v1/documents/{id}", id)
                        .with(jwt().jwt(t -> t.subject("support-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/documents/{id}", id)
                        .with(jwt().jwt(t -> t.subject("admin-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"))))
                .andExpect(status().isNoContent());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    private int searchRows(String documentId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from search_document where entity_type = 'document' and entity_id = ?",
                Integer.class, documentId);
        return n == null ? 0 : n;
    }

    private void seedMember(UUID orgId, String subject, boolean manager) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing", // partial unique (V17)
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'DocRole', false, 0, now())", roleId, orgId, "DOCS_" + subject.toUpperCase());
        jdbc.update("insert into role_permission (role_id, permission) values (?, 'DOCUMENT_READ')", roleId);
        if (manager) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, 'DOCUMENT_MANAGE')", roleId);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
