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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The file REST surface: upload namespaces the key under the caller, download 302s to a presigned URL,
 * and every read/delete/presign is owner-or-admin scoped. The storage provider is mocked — the real
 * S3/SeaweedFS round-trip is covered by {@code FileStorageIntegrationTest}.
 */
@AutoConfigureMockMvc
class FileApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileStorageProvider storage;

    private static RequestPostProcessor user(String subject) {
        return jwt().jwt(builder -> builder.subject(subject));
    }

    @Test
    void uploadStoresUnderTheCallersNamespaceAndReturnsTheKey() throws Exception {
        String sub = "user-" + UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "hello.txt", "text/plain", "hi there".getBytes());

        mockMvc.perform(multipart("/api/v1/files").file(file).with(user(sub)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("file"))
                .andExpect(jsonPath("$.data.attributes.key", Matchers.startsWith("u/" + sub + "/")))
                .andExpect(jsonPath("$.data.attributes.key", Matchers.endsWith("hello.txt")))
                .andExpect(jsonPath("$.data.attributes.contentType").value("text/plain"));

        then(storage).should().put(startsWith("u/" + sub + "/"), any(), anyLong(), any());
    }

    @Test
    void downloadRedirectsToAPresignedUrlForTheOwner() throws Exception {
        String sub = "user-" + UUID.randomUUID();
        String key = "u/" + sub + "/" + UUID.randomUUID() + "/report.pdf";
        given(storage.exists(key)).willReturn(true);
        given(storage.presignGet(org.mockito.ArgumentMatchers.eq(key), any()))
                .willReturn(URI.create("https://storage.local/signed?token=abc").toURL());

        mockMvc.perform(get("/api/v1/files/" + key).with(user(sub)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://storage.local/signed?token=abc"));
    }

    @Test
    void aCallerCannotDownloadAnotherUsersFile() throws Exception {
        String me = "user-" + UUID.randomUUID();
        String key = "u/someone-else/" + UUID.randomUUID() + "/secret.pdf";

        mockMvc.perform(get("/api/v1/files/" + key).with(user(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        then(storage).should(never()).presignGet(any(), any());
    }

    @Test
    void adminMayReachAnyNamespace() throws Exception {
        String key = "u/anybody/" + UUID.randomUUID() + "/audit.log";
        given(storage.exists(key)).willReturn(true);
        given(storage.presignGet(any(), any())).willReturn(URI.create("https://storage.local/x").toURL());

        mockMvc.perform(get("/api/v1/files/" + key)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isFound());
    }

    @Test
    void deleteRemovesAnOwnedObject() throws Exception {
        String sub = "user-" + UUID.randomUUID();
        String key = "u/" + sub + "/" + UUID.randomUUID() + "/old.csv";

        mockMvc.perform(delete("/api/v1/files/" + key).with(user(sub)))
                .andExpect(status().isNoContent());

        then(storage).should().delete(key);
    }

    @Test
    void presignPutMintsAnUploadUrlUnderTheCallersNamespace() throws Exception {
        String sub = "user-" + UUID.randomUUID();
        given(storage.presignPut(startsWith("u/" + sub + "/"), any(), any()))
                .willReturn(URI.create("https://storage.local/put?token=xyz").toURL());

        mockMvc.perform(post("/api/v1/files/presign").with(user(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"PUT\",\"key\":\"export.csv\",\"contentType\":\"text/csv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("file-presign"))
                .andExpect(jsonPath("$.data.attributes.operation").value("PUT"))
                .andExpect(jsonPath("$.data.attributes.url").value("https://storage.local/put?token=xyz"))
                .andExpect(jsonPath("$.data.attributes.key", Matchers.startsWith("u/" + sub + "/")));
    }

    @Test
    void emptyUploadIs422() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        mockMvc.perform(multipart("/api/v1/files").file(empty).with(user("user-" + UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("file"));
    }
}
