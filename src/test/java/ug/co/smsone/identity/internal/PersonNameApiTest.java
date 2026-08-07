package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.admin;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.subjectOf;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.support;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The person-name API over HTTP, on both surfaces: a person correcting their own name at
 * {@code /api/v1/me/name}, and an operator correcting someone else's at
 * {@code /api/v1/admin/users/{personId}/name}.
 *
 * <p>Three of these tests exist because of a specific way this endpoint shape goes wrong, and each
 * fails loudly if the guard is removed:
 * <ul>
 *   <li><b>Omission.</b> Seven mostly-null components make a PUT-shaped write erase whatever the client
 *       did not send. {@link #aPatchChangesOnlyTheComponentsItSends} pins absent ≠ null.</li>
 *   <li><b>Derivation.</b> {@link #formattedNameSurvivesAPatchOfTheParts} pins the rule V10 states —
 *       the display value is supplied whole or absent, NEVER composed from the parts, because
 *       given-then-family is one naming convention among several.</li>
 *   <li><b>Reach.</b> {@link #onlyAPlatformAdminMayCorrectSomebodyElse} pins that editing another
 *       human's identity is not something a self-service token or a read-only support login can do,
 *       and that a refusal leaves the row untouched.</li>
 * </ul>
 *
 * <p>Assertions about what was actually stored go through {@link JdbcTemplate} against the seven
 * {@code person} columns rather than through the repository. A projection can agree with a bug — if
 * {@code formatted_name} were being recomputed on read, an entity round-trip would hide it and the
 * column would not.
 */
@AutoConfigureMockMvc
class PersonNameApiTest extends AbstractIntegrationTest {

    private static final String ME = "/api/v1/me";
    private static final String ADMIN = "/api/v1/admin/users";

    /** A fully-populated name, so every test can assert the six components it did NOT touch. */
    private static final PersonName FULL = new PersonName("Dr. Ada M. Lovelace-Byron MBE", "Ada",
            "Lovelace-Byron", "Miriam", "Dr.", "MBE", "Addy");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PersonRepository persons;

    @Autowired
    private ExternalIdentityRepository identities;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aPersonSeesTheirOwnNameBlockOnMe() throws Exception {
        UUID personId = person(FULL);

        mockMvc.perform(get(ME).with(self(personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.name.formattedName")
                        .value("Dr. Ada M. Lovelace-Byron MBE"))
                .andExpect(jsonPath("$.data.attributes.name.givenName").value("Ada"))
                .andExpect(jsonPath("$.data.attributes.name.familyName").value("Lovelace-Byron"))
                .andExpect(jsonPath("$.data.attributes.name.middleName").value("Miriam"))
                .andExpect(jsonPath("$.data.attributes.name.honorificPrefix").value("Dr."))
                .andExpect(jsonPath("$.data.attributes.name.honorificSuffix").value("MBE"))
                .andExpect(jsonPath("$.data.attributes.name.preferredName").value("Addy"));
    }

    /**
     * The omission bug this payload shape invites. A body naming one component must leave the other six
     * exactly as they were — the failure mode being pinned is a write that nulls every column the
     * client did not mention, which for a name is most of them.
     */
    @Test
    void aPatchChangesOnlyTheComponentsItSends() throws Exception {
        UUID personId = person(FULL);

        patchSelf(personId, "{\"givenName\":\"Augusta\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("person-name"))
                .andExpect(jsonPath("$.data.attributes.givenName").value("Augusta"));

        assertThat(stored(personId)).isEqualTo(new PersonName("Dr. Ada M. Lovelace-Byron MBE", "Augusta",
                "Lovelace-Byron", "Miriam", "Dr.", "MBE", "Addy"));
    }

    /** Absent leaves alone; explicit null clears. Both halves in one test — they are one rule. */
    @Test
    void anExplicitNullClearsAComponentAndAnAbsentOneDoesNot() throws Exception {
        UUID personId = person(FULL);

        patchSelf(personId, "{\"honorificSuffix\":null}")
                .andExpect(status().isOk())
                // Cleared, so omitted from the response entirely — absent means "we were never told",
                // which is exactly what the column now holds.
                .andExpect(jsonPath("$.data.attributes.honorificSuffix").doesNotExist())
                .andExpect(jsonPath("$.data.attributes.honorificPrefix").value("Dr."));

        assertThat(stored(personId).honorificSuffix()).isNull();
        assertThat(stored(personId).honorificPrefix()).isEqualTo("Dr."); // the neighbour survived
    }

    /**
     * A blank string is not a name, so {@code ""} clears rather than storing whitespace — otherwise a
     * form that submits an untouched empty input writes a space into a column that means "unknown".
     */
    @Test
    void aBlankValueClearsRatherThanStoringWhitespace() throws Exception {
        UUID personId = person(FULL);

        patchSelf(personId, "{\"middleName\":\"   \",\"preferredName\":\"  Addie  \"}")
                .andExpect(status().isOk());

        assertThat(stored(personId).middleName()).isNull();
        assertThat(stored(personId).preferredName()).isEqualTo("Addie"); // trimmed, not blanked
    }

    /**
     * <b>The rule that matters most.</b> {@code formatted_name} is the display value and is supplied
     * whole or not at all. Changing the parts must not touch it, and must not trigger any "recompose it
     * from given + family" fallback: name ORDER is cultural, so an assembled display string prints many
     * people's names backwards. V10 and {@link PersonName} both state this; this test is what stops it
     * being quietly re-introduced as a convenience.
     */
    @Test
    void formattedNameSurvivesAPatchOfTheParts() throws Exception {
        UUID personId = person(FULL);

        patchSelf(personId, "{\"givenName\":\"Augusta\",\"familyName\":\"Byron\",\"middleName\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.formattedName")
                        .value("Dr. Ada M. Lovelace-Byron MBE"));

        // Still the ORIGINAL string — not "Augusta Byron", and not "Dr. Augusta Byron MBE".
        assertThat(stored(personId).formattedName()).isEqualTo("Dr. Ada M. Lovelace-Byron MBE");
    }

    /**
     * And the other direction: clearing every part leaves the display value standing, and clearing the
     * display value does not resurrect one from the parts. A person carrying only a {@code
     * formattedName} — or nothing at all — is an ordinary state, not an error.
     */
    @Test
    void aPersonWithNoNameAtAllIsSaveable() throws Exception {
        UUID personId = person(FULL);

        patchSelf(personId, """
                {"formattedName":null,"givenName":null,"familyName":null,"middleName":null,
                 "honorificPrefix":null,"honorificSuffix":null,"preferredName":null}""")
                .andExpect(status().isOk());

        assertThat(stored(personId)).isEqualTo(PersonName.UNKNOWN);
        // /me still renders: an empty name object, not a 500 and not a missing key.
        mockMvc.perform(get(ME).with(self(personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.name").exists())
                .andExpect(jsonPath("$.data.attributes.name.formattedName").doesNotExist());
    }

    /**
     * A typo'd key that quietly did nothing looks exactly like a save that worked, which is the price of
     * a map body and the reason it is refused instead.
     */
    @Test
    void anUnknownComponentIsRefusedRatherThanIgnored() throws Exception {
        UUID personId = person(FULL);

        patchSelf(personId, "{\"familyname\":\"Byron\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].source.pointer")
                        .value("/data/attributes/familyname"));

        assertThat(stored(personId)).isEqualTo(FULL); // refused writes nothing
    }

    /** Over a column's width is a 422 naming the field, not a 500 at flush. */
    @Test
    void anOverLongComponentIsRefusedNamingTheField() throws Exception {
        UUID personId = person(FULL);

        patchSelf(personId, "{\"givenName\":\"" + "a".repeat(101) + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/givenName"));

        assertThat(stored(personId)).isEqualTo(FULL);
    }

    /**
     * The reach guard. A person may correct themselves; the admin path is closed both to a plain
     * self-service token and to a read-only support login, and each refusal must leave the row alone —
     * a 403 that still wrote is the failure this asserts against.
     */
    @Test
    void onlyAPlatformAdminMayCorrectSomebodyElse() throws Exception {
        UUID subject = person(FULL);
        UUID stranger = person(FULL);
        UUID operator = person(FULL);

        // A plain authenticated person, editing someone else: no platform role at all.
        mockMvc.perform(patch(ADMIN + "/" + subject + "/name")
                        .with(self(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\":\"Mallory\"}"))
                .andExpect(status().isForbidden());
        assertThat(stored(subject)).isEqualTo(FULL);

        // platform-support reads this surface but does not rewrite identities on it.
        mockMvc.perform(patch(ADMIN + "/" + subject + "/name")
                        .with(support(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\":\"Mallory\"}"))
                .andExpect(status().isForbidden());
        assertThat(stored(subject)).isEqualTo(FULL);

        // Their own name, though, is theirs to fix.
        patchSelf(subject, "{\"givenName\":\"Augusta\"}").andExpect(status().isOk());
        assertThat(stored(subject).givenName()).isEqualTo("Augusta");
    }

    /** The operator read has to show the components a typo is IN, not just the display value. */
    @Test
    void theOperatorReadShowsTheWholeNameBlock() throws Exception {
        UUID subject = person(FULL);
        UUID operator = person(FULL);

        mockMvc.perform(get(ADMIN + "/" + subject).with(support(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.personId").value(subject.toString()))
                .andExpect(jsonPath("$.data.attributes.name.formattedName")
                        .value("Dr. Ada M. Lovelace-Byron MBE"))
                .andExpect(jsonPath("$.data.attributes.name.givenName").value("Ada"))
                .andExpect(jsonPath("$.data.attributes.name.honorificSuffix").value("MBE"));
    }

    /**
     * An admin editing another human's name is exactly the event {@code audit_log} exists for. The row
     * must name the operator (not the person changed), the person changed, and the before and after of
     * the components the request touched — a trail saying only "a name changed" cannot answer the
     * question a review asks.
     */
    @Test
    void anAdminCorrectionIsAudited() throws Exception {
        UUID subject = person(FULL);
        UUID operator = person(FULL);

        mockMvc.perform(patch(ADMIN + "/" + subject + "/name")
                        .with(admin(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\":\"Augusta\",\"honorificSuffix\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.givenName").value("Augusta"));

        Map<String, Object> row = jdbc.queryForMap("""
                select actor_person_id, from_state, to_state
                from audit_log where action = 'identity.person_name_changed' and target = ?
                """, subject.toString());

        // The operator is accountable, not the person whose name it is.
        assertThat(row.get("actor_person_id")).hasToString(operator.toString());
        // Only the sent components, and both sides of each.
        assertThat(row.get("from_state")).hasToString("givenName='Ada' honorificSuffix='MBE'");
        assertThat(row.get("to_state")).hasToString("givenName='Augusta' honorificSuffix=null");
    }

    /**
     * Re-sending the name a client just read is the ordinary case, not an odd one, so it must not file
     * an audit row claiming a change that did not happen.
     */
    @Test
    void aPatchThatChangesNothingIsNotAudited() throws Exception {
        UUID subject = person(FULL);
        UUID operator = person(FULL);

        mockMvc.perform(patch(ADMIN + "/" + subject + "/name")
                        .with(admin(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\":\"Ada\"}"))
                .andExpect(status().isOk());

        assertThat(auditRowCount(subject)).isZero();
    }

    private ResultActions patchSelf(UUID personId, String body) throws Exception {
        return mockMvc.perform(patch(ME + "/name")
                .with(self(personId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * A plain authenticated person: a linked token subject and NO platform authority. The {@code iss}
     * matters as much as the subject — {@code external_identity} is keyed on the pair, so a token from
     * another issuer resolves to no person at all.
     */
    private static JwtRequestPostProcessor self(UUID personId) {
        return jwt().jwt(token -> token.subject(subjectOf(personId)).claim("iss", EdgeSeed.ISSUER));
    }

    /** An ACTIVE person carrying {@code name}, reachable by {@link #self}. */
    private UUID person(PersonName name) {
        Person person = Person.invited(name, Instant.now());
        person.activate(Instant.now());
        UUID personId = persons.save(person).getId();
        identities.save(ExternalIdentity.link(personId, IdentityProvider.KEYCLOAK, EdgeSeed.ISSUER,
                subjectOf(personId), null, Instant.now()));
        return personId;
    }

    /**
     * The seven columns as they actually stand, read outside JPA on purpose — an entity round-trip
     * would agree with a {@code formatted_name} that was being recomputed on read, and the column
     * would not.
     */
    private PersonName stored(UUID personId) {
        return jdbc.queryForObject("""
                select formatted_name, given_name, family_name, middle_name,
                       honorific_prefix, honorific_suffix, preferred_name
                from person where id = ?
                """, (rs, row) -> new PersonName(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), personId);
    }

    private int auditRowCount(UUID personId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from audit_log
                where action = 'identity.person_name_changed' and target = ?
                """, Integer.class, personId.toString());
        return count == null ? 0 : count;
    }
}
