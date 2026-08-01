package ug.co.smsone.profile.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.identity.UserDirectory;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The self-service identity surface: profile round-trips with contacts (and validates them),
 * preferences upsert additively (null deletes), the avatar follows the files pattern with
 * old-object cleanup, linked accounts are the read-only IdP view, and a dual member sees both
 * organizations with their role in each — the list an org switcher renders.
 */
@AutoConfigureMockMvc
class ProfileApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FileStorageProvider storage;

    @MockitoBean
    private UserDirectory userDirectory;

    @Test
    void profilePreferencesAndAvatarRoundTrip() throws Exception {
        String subject = "profile-" + UUID.randomUUID();
        var me = jwt().jwt(t -> t.subject(subject));

        // A never-saved profile still answers — empty, not 404.
        mockMvc.perform(get("/api/v1/me/profile").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.hasAvatar").value(false));

        mockMvc.perform(put("/api/v1/me/profile").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Ada O.","phone":"+256700000001","timezone":"Africa/Kampala",
                                 "locale":"en-UG","contacts":[
                                   {"kind":"email","value":"ada@acme.test","label":"work","primary":true},
                                   {"kind":"PHONE","value":"+256700000001","label":null,"primary":false}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.displayName").value("Ada O."))
                .andExpect(jsonPath("$.data.attributes.contacts.length()").value(2))
                .andExpect(jsonPath("$.data.attributes.contacts[0].kind").value("EMAIL"));

        mockMvc.perform(put("/api/v1/me/profile").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contacts\":[{\"kind\":\"CARRIER-PIGEON\",\"value\":\"coop 4\"}]}"))
                .andExpect(status().isUnprocessableContent());

        // Preferences: additive, null deletes.
        mockMvc.perform(put("/api/v1/me/preferences").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"dark\",\"digest\":\"weekly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("dark"));
        mockMvc.perform(put("/api/v1/me/preferences").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"digest\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("dark"))
                .andExpect(jsonPath("$.data.digest").doesNotExist());

        // Avatar: upload (201) → 302 → replace deletes the OLD object → remove.
        given(storage.exists(anyString())).willReturn(true);
        given(storage.presignGet(anyString(), any()))
                .willReturn(URI.create("http://storage.local/avatar").toURL());
        mockMvc.perform(multipart("/api/v1/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", "png-bytes".getBytes()))
                        .with(me))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.hasAvatar").value(true));
        mockMvc.perform(get("/api/v1/me/avatar").with(me)).andExpect(status().isFound());
        mockMvc.perform(multipart("/api/v1/me/avatar")
                        .file(new MockMultipartFile("file", "new.png", "image/png", "png2".getBytes()))
                        .with(me))
                .andExpect(status().isCreated());
        then(storage).should().delete(anyString()); // the replaced object went
        mockMvc.perform(delete("/api/v1/me/avatar").with(me)).andExpect(status().isNoContent());
        given(storage.exists(anyString())).willReturn(false);
        mockMvc.perform(get("/api/v1/me/avatar").with(me)).andExpect(status().isNotFound());

        // A text file is not an avatar.
        mockMvc.perform(multipart("/api/v1/me/avatar")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hi".getBytes()))
                        .with(me))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void linkedAccountsAreTheReadOnlyIdpView() throws Exception {
        String subject = "linked-" + UUID.randomUUID();
        given(userDirectory.linkedAccounts(subject)).willReturn(List.of(
                new UserDirectory.LinkedAccount("github", "ada-codes"),
                new UserDirectory.LinkedAccount("google", "ada@gmail.test")));
        mockMvc.perform(get("/api/v1/me/linked-accounts").with(jwt().jwt(t -> t.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].attributes.provider").value("github"));
    }

    @Test
    void aDualMemberSeesBothOrganizationsWithTheirRoleInEach() throws Exception {
        String subject = "dual-" + UUID.randomUUID();
        UUID orgA = seedOrgWithMember(subject, "alpha-" + UUID.randomUUID().toString().substring(0, 8), "ADMIN");
        seedOrgWithMember(subject, "beta-" + UUID.randomUUID().toString().substring(0, 8), "MEMBER");

        mockMvc.perform(get("/api/v1/me/organizations").with(jwt().jwt(t -> t.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.id=='" + orgA + "')].attributes.roleCode").value("ADMIN"));
    }

    @Test
    void supportReadsAProfileTheOwnerWrote() throws Exception {
        String subject = "seen-" + UUID.randomUUID();
        mockMvc.perform(put("/api/v1/me/profile").with(jwt().jwt(t -> t.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Seen User\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/users/{subject}/profile", subject)
                        .with(jwt().jwt(t -> t.subject("support-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.displayName").value("Seen User"));
        mockMvc.perform(get("/api/v1/admin/users/{subject}/profile", subject)
                        .with(jwt().jwt(t -> t.subject("nobody"))))
                .andExpect(status().isForbidden());
    }

    private UUID seedOrgWithMember(String subject, String alias, String roleCode) {
        UUID orgId = UUID.randomUUID();
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now())",
                UUID.randomUUID(), orgId, alias, "Org " + alias);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, ?, false, 0, now())", roleId, orgId, roleCode, roleCode);
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
        return orgId;
    }
}
