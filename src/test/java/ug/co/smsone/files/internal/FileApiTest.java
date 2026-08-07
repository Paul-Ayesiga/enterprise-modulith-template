package ug.co.smsone.files.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The file REST surface: upload namespaces the key under the caller, download 302s to a presigned URL,
 * and every read/delete/presign is owner-scoped — with cross-namespace access tiered by blast radius
 * (support reads, admin deletes). The storage provider is mocked — the real S3/SeaweedFS round-trip is
 * covered by {@code FileStorageIntegrationTest}.
 *
 * <p><b>The namespace is {@code u/<person-id>/}, not {@code u/<sub>/}.</b> {@code FileController} mints
 * and matches keys from {@code CurrentUser.personId()}, so a caller here is a seeded {@code person} and
 * the expected key prefix is their {@code person.id} — a token subject is a provider string that the
 * edge translates away before any controller sees it, and it never appears in an object key.
 */
@AutoConfigureMockMvc
class FileApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FileStorageProvider storage;

    /** A person the edge can resolve — every personal-namespace endpoint refuses a caller who is not one. */
    private UUID seedPerson() {
        return EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
    }

    /**
     * A token that resolves to {@code personId}. The {@code iss} claim is load-bearing:
     * {@code external_identity} is keyed on (issuer, subject), so a token without it resolves to no
     * person, the caller owns no namespace, and every request here 403s.
     */
    private RequestPostProcessor person(UUID personId) {
        String subject = jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ?", String.class, personId);
        return jwt().jwt(builder -> builder.subject(subject).claim("iss", EdgeSeed.ISSUER));
    }

    /** A platform operator at exactly one tier — the ladder supplies the rest. */
    private static RequestPostProcessor platform(String role) {
        return jwt().jwt(builder -> builder.subject("platform-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void uploadStoresUnderTheCallersNamespaceAndReturnsTheKey() throws Exception {
        UUID me = seedPerson();
        MockMultipartFile file = new MockMultipartFile("file", "hello.txt", "text/plain", "hi there".getBytes());

        mockMvc.perform(multipart("/api/v1/files").file(file).with(person(me)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("file"))
                .andExpect(jsonPath("$.data.attributes.key", Matchers.startsWith("u/" + me + "/")))
                .andExpect(jsonPath("$.data.attributes.key", Matchers.endsWith("hello.txt")))
                .andExpect(jsonPath("$.data.attributes.contentType").value("text/plain"));

        then(storage).should().put(startsWith("u/" + me + "/"), any(), anyLong(), any());
    }

    @Test
    void downloadRedirectsToAPresignedUrlForTheOwner() throws Exception {
        UUID me = seedPerson();
        String key = "u/" + me + "/" + UUID.randomUUID() + "/report.pdf";
        given(storage.exists(key)).willReturn(true);
        given(storage.presignGet(org.mockito.ArgumentMatchers.eq(key), any()))
                .willReturn(URI.create("https://storage.local/signed?token=abc").toURL());

        mockMvc.perform(get("/api/v1/files/" + key).with(person(me)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://storage.local/signed?token=abc"));
    }

    @Test
    void aCallerCannotDownloadAnotherUsersFile() throws Exception {
        UUID me = seedPerson();
        UUID someoneElse = seedPerson();
        String key = "u/" + someoneElse + "/" + UUID.randomUUID() + "/secret.pdf";

        mockMvc.perform(get("/api/v1/files/" + key).with(person(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        then(storage).should(never()).presignGet(any(), any());
    }

    @Test
    void supportMayReadAnyNamespace() throws Exception {
        String key = "u/anybody/" + UUID.randomUUID() + "/audit.log";
        given(storage.exists(key)).willReturn(true);
        given(storage.presignGet(any(), any())).willReturn(URI.create("https://storage.local/x").toURL());

        mockMvc.perform(get("/api/v1/files/" + key).with(platform(PlatformRole.SUPPORT)))
                .andExpect(status().isFound());
    }

    @Test
    void supportMayNotDeleteAnotherUsersFile() throws Exception {
        String key = "u/anybody/" + UUID.randomUUID() + "/audit.log";

        mockMvc.perform(delete("/api/v1/files/" + key).with(platform(PlatformRole.SUPPORT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        then(storage).should(never()).delete(any());
    }

    @Test
    void adminMayDeleteAnyNamespace() throws Exception {
        String key = "u/anybody/" + UUID.randomUUID() + "/audit.log";

        mockMvc.perform(delete("/api/v1/files/" + key).with(platform(PlatformRole.ADMIN)))
                .andExpect(status().isNoContent());

        then(storage).should().delete(key);
    }

    /** The ladder reaches hand-rolled checks too, not just {@code @PreAuthorize}. */
    @Test
    void superadminInheritsTheDeleteTier() throws Exception {
        String key = "u/anybody/" + UUID.randomUUID() + "/audit.log";

        mockMvc.perform(delete("/api/v1/files/" + key).with(platform(PlatformRole.SUPERADMIN)))
                .andExpect(status().isNoContent());

        then(storage).should().delete(key);
    }

    @Test
    void deleteRemovesAnOwnedObject() throws Exception {
        UUID me = seedPerson();
        String key = "u/" + me + "/" + UUID.randomUUID() + "/old.csv";

        mockMvc.perform(delete("/api/v1/files/" + key).with(person(me)))
                .andExpect(status().isNoContent());

        then(storage).should().delete(key);
    }

    @Test
    void presignPutMintsAnUploadUrlUnderTheCallersNamespace() throws Exception {
        UUID me = seedPerson();
        given(storage.presignPut(startsWith("u/" + me + "/"), any(), any()))
                .willReturn(URI.create("https://storage.local/put?token=xyz").toURL());

        mockMvc.perform(post("/api/v1/files/presign").with(person(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"PUT\",\"key\":\"export.csv\",\"contentType\":\"text/csv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("file-presign"))
                .andExpect(jsonPath("$.data.attributes.operation").value("PUT"))
                .andExpect(jsonPath("$.data.attributes.url").value("https://storage.local/put?token=xyz"))
                .andExpect(jsonPath("$.data.attributes.key", Matchers.startsWith("u/" + me + "/")));
    }

    @Test
    void emptyUploadIs422() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        mockMvc.perform(multipart("/api/v1/files").file(empty).with(person(seedPerson())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("file"));
    }
}
