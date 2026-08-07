package ug.co.smsone.profile.internal;

import static org.mockito.ArgumentMatchers.any;
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
import ug.co.smsone.identity.PersonDirectory;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The self-service identity surface: the profile round-trips, preferences upsert additively (null
 * deletes), the avatar follows the files pattern with old-object cleanup, linked accounts are the
 * read-only IdP view, and a dual member sees both organizations with their role in each — the list an
 * org switcher renders.
 *
 * <p><b>Contacts are no longer part of this surface</b>, and the assertions that covered them are gone
 * rather than repaired. V28 moved them to {@code person_contact} in {@code identity}, where an address
 * has a verification lifecycle and a proven one is globally unique — neither of which a whole-document
 * PUT that replaces the list can express without silently discarding proof of ownership.
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
    private PersonDirectory persons;

    @Test
    void profilePreferencesAndAvatarRoundTrip() throws Exception {
        var me = tokenFor(seedPerson());

        // A never-saved profile still answers — empty, not 404.
        mockMvc.perform(get("/api/v1/me/profile").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.hasAvatar").value(false));

        mockMvc.perform(put("/api/v1/me/profile").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Ada O.","timezone":"Africa/Kampala","locale":"en-UG"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.displayName").value("Ada O."))
                .andExpect(jsonPath("$.data.attributes.timezone").value("Africa/Kampala"));

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
        UUID personId = seedPerson();
        given(persons.linkedAccounts(personId)).willReturn(List.of(
                new PersonDirectory.LinkedAccount("github", "ada-codes"),
                new PersonDirectory.LinkedAccount("google", "ada@gmail.test")));
        mockMvc.perform(get("/api/v1/me/linked-accounts").with(tokenFor(personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].attributes.provider").value("github"));
    }

    @Test
    void aDualMemberSeesBothOrganizationsWithTheirRoleInEach() throws Exception {
        UUID personId = seedPerson();
        UUID orgA = seedOrgWithMember(personId, "alpha-" + UUID.randomUUID().toString().substring(0, 8), "ADMIN");
        seedOrgWithMember(personId, "beta-" + UUID.randomUUID().toString().substring(0, 8), "MEMBER");

        mockMvc.perform(get("/api/v1/me/organizations").with(tokenFor(personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.id=='" + orgA + "')].attributes.roleCode").value("ADMIN"));
    }

    @Test
    void supportReadsAProfileTheOwnerWrote() throws Exception {
        UUID personId = seedPerson();
        mockMvc.perform(put("/api/v1/me/profile").with(tokenFor(personId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Seen User\"}"))
                .andExpect(status().isOk());
        // The path segment is a person id now — the same route, a different id space.
        mockMvc.perform(get("/api/v1/admin/users/{personId}/profile", personId)
                        .with(jwt().jwt(t -> t.subject("support-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.displayName").value("Seen User"));
        mockMvc.perform(get("/api/v1/admin/users/{personId}/profile", personId)
                        .with(jwt().jwt(t -> t.subject("nobody"))))
                .andExpect(status().isForbidden());
    }

    /** A person the edge can resolve — every endpoint here refuses a caller who is not one. */
    private UUID seedPerson() {
        return EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor tokenFor(UUID personId) {
        String subject = jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ?", String.class, personId);
        return jwt().jwt(t -> t.subject(subject));
    }

    private UUID seedOrgWithMember(UUID personId, String alias, String roleCode) {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), alias);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, ?, false, 0, now())", roleId, orgId, roleCode, roleCode);
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        return orgId;
    }
}
