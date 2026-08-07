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

    /**
     * The multi-org caller the whole design leans on and the seed never contained — see
     * {@link EdgeSeed#multiOrgPerson}, which explains why 193,940 seeded people proved nothing here.
     *
     * <p>Every assertion is about the RESULT, deliberately, and none about how the result was produced.
     * {@code OrgMembershipsController} batches the role lookup by role id so this caller — the one
     * whose rows span organizations by definition — does not pay a query per organization, and ADR 0010
     * §5 item 4 records that the batch stops being possible once role rows live in per-tenant schemas.
     * When that day comes the endpoint may legitimately go to 1+N queries; what it may never do is come
     * back with a missing organization, a null role, or org A wearing org B's role — and those are the
     * three things asserted below.
     */
    @Test
    void aDualMemberSeesBothOrganizationsWithTheirRoleInEach() throws Exception {
        // DISTINCT role codes: two orgs both answering "ADMIN" cannot tell a correct per-org mapping
        // from an implementation that renders the first role code it found for every row.
        var dual = EdgeSeed.multiOrgPerson(jdbc, "ADMIN", "SUPPORT");

        var response = mockMvc.perform(get("/api/v1/me/organizations").with(tokenFor(dual.subject())))
                .andExpect(status().isOk())
                // Exactly two, not "at least": this runs against the container every other test class
                // seeds its organizations into, so a listing that ever stopped scoping to the caller
                // would come back with hundreds.
                .andExpect(jsonPath("$.data.length()").value(2));
        for (EdgeSeed.OrgSeat seat : dual.seats()) {
            // Selected by id rather than by index — findByPersonIdAndStatus has no ORDER BY, so which
            // organization lands first is Postgres's choice and not a contract.
            String row = "$.data[?(@.id=='" + seat.organizationId() + "')]";
            response.andExpect(jsonPath(row + ".type").value("my-organization"))
                    .andExpect(jsonPath(row + ".attributes.roleCode").value(seat.roleCode()))
                    // The org's OWN slug, not the external_alias the token's claim resolves through.
                    .andExpect(jsonPath(row + ".attributes.alias").value(seat.alias()))
                    .andExpect(jsonPath(row + ".attributes.status").value("ACTIVE"));
        }
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

    /**
     * A token that resolves to {@code personId}. The {@code iss} claim is load-bearing:
     * {@code external_identity} is keyed on (issuer, subject), so a token without it reaches no person
     * — every {@code /me} endpoint then answers 403 and {@code /me/organizations} an empty list.
     */
    private org.springframework.test.web.servlet.request.RequestPostProcessor tokenFor(UUID personId) {
        return tokenFor(jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ?", String.class, personId));
    }

    /** The same, for a caller who already holds the subject — one fewer round trip and one fewer join. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(t -> t.subject(subject).claim("iss", EdgeSeed.ISSUER));
    }
}
