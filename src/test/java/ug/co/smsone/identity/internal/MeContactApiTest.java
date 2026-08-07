package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ug.co.smsone.identity.PersonDirectory;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.KeycloakUser;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The {@code /me/contacts} surface, and — more importantly — the rule the surface exists to be safe
 * under: <b>an address nobody has proven is inert.</b>
 *
 * <p>Before this slice {@code person_contact} was write-once (a provisioning invite) and read by
 * everything, so "unverified" was a column nothing consulted. The moment a person can add a row to that
 * table, every reader that turns an ADDRESS into a PERSON becomes an account-takeover primitive unless
 * it insists on a proof, and two of them exist:
 * {@link PersonDirectory#findPersonIdByEmail} and the find-or-create probe inside provisioning.
 * {@link #anUnprovenClaimNeverResolvesAPersonByAddress} and
 * {@link #anUnprovenClaimCannotHijackSomebodyElsesInvite} are this class's reason to exist; the rest
 * pin the surface around them.
 *
 * <p>Assertions about what was stored go through {@link JdbcTemplate} rather than the repository: the
 * primary flag is governed by a PARTIAL unique index and a Hibernate flush order, and an entity
 * round-trip would happily agree with an in-memory state the database never accepted.
 */
@AutoConfigureMockMvc
class MeContactApiTest extends AbstractIntegrationTest {

    private static final String CONTACTS = "/api/v1/me/contacts";

    /** Mocked for the provisioning test only; the real round-trip is the Phase-4 end-to-end IT. */
    @MockitoBean
    private KeycloakUserAdminGateway keycloak;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonDirectory directory;

    @Autowired
    private PersonProvisioning provisioning;

    // ---------------------------------------------------------------------------------------------
    // The two rules that make the write surface safe to have at all
    // ---------------------------------------------------------------------------------------------

    /**
     * The directory resolves BY address, so if a claim satisfied it, adding one would hand you whoever
     * you named. Both directions in one test because they are one rule: the claim is invisible, the
     * proof is what makes it visible.
     */
    @Test
    void anUnprovenClaimNeverResolvesAPersonByAddress() throws Exception {
        String address = address("target");
        UUID claimant = personInvitedAt(address("claimant"));

        String contactId = added(claimant, "EMAIL", address);
        assertThat(directory.findPersonIdByEmail(address))
                .as("an unproven claim must resolve nobody")
                .isEmpty();

        prove(claimant, address, contactId).andExpect(status().isOk());
        assertThat(directory.findPersonIdByEmail(address)).contains(claimant);
    }

    /**
     * <b>The takeover this whole slice had to be built around.</b> An attacker parks an unproven claim on
     * an address nobody holds yet; an administrator later invites that address to their organization.
     * Provisioning's find-or-create must NOT hand back the attacker's person — because
     * {@code provision()} would then see their existing Keycloak link, report "already provisioned",
     * send no invite at all, and the caller would add the ATTACKER to the org while the real human never
     * hears about it.
     *
     * <p>The second half is the other side of the same predicate: a re-invite to an address an account
     * was actually established at still has to find that account, or every provisioning retry would mint
     * a duplicate person.
     */
    @Test
    void anUnprovenClaimCannotHijackSomebodyElsesInvite() {
        String victim = address("victim");
        UUID attacker = personInvitedAt(address("attacker"));
        added(attacker, "EMAIL", victim);

        String subject = "kc-" + UUID.randomUUID();
        given(keycloak.findByEmail(victim)).willReturn(Optional.empty());
        given(keycloak.createUser(victim, "V", "K")).willReturn(new KeycloakUser(subject, victim));

        ProvisionedPerson provisioned = provisioning.provision(new ProvisionRequest(victim, "V", "K"));

        assertThat(provisioned.personId()).isNotEqualTo(attacker);
        assertThat(provisioned.alreadyExisted())
                .as("a parked claim must not make an invite look like an existing account")
                .isFalse();

        // ...and the row provisioning DID write (primary, at the address it invited) is still what a
        // retry finds, so the predicate did not break idempotent re-invites.
        given(keycloak.findByEmail(victim)).willReturn(Optional.of(new KeycloakUser(subject, victim)));
        assertThat(provisioning.provision(new ProvisionRequest(victim, "V", "K")).personId())
                .isEqualTo(provisioned.personId());
    }

    // ---------------------------------------------------------------------------------------------
    // Adding
    // ---------------------------------------------------------------------------------------------

    @Test
    void theInviteAddressIsListedAsAnUnprovenPrimary() throws Exception {
        String invited = address("invited");
        UUID personId = personInvitedAt(invited);

        mockMvc.perform(get(CONTACTS).with(self(personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("contact"))
                .andExpect(jsonPath("$.data[0].attributes.value").value(invited))
                .andExpect(jsonPath("$.data[0].attributes.primary").value(true))
                // Primary but unproven: exactly what an invite leaves behind, and the reason
                // "primary implies verified" cannot be a CHECK constraint in the schema.
                .andExpect(jsonPath("$.data[0].attributes.verified").value(false));
    }

    @Test
    void anAddedAddressArrivesUnprovenAndNotPrimary() throws Exception {
        UUID personId = personInvitedAt(address("owner"));
        String extra = address("extra");

        add(personId, "EMAIL", extra, "work")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.value").value(extra))
                .andExpect(jsonPath("$.data.attributes.label").value("work"))
                .andExpect(jsonPath("$.data.attributes.primary").value(false))
                .andExpect(jsonPath("$.data.attributes.verified").value(false));

        assertThat(rows(personId)).hasSize(2);
    }

    /** Folded on the way in, because the unique index folds too — two spellings are one address. */
    @Test
    void anEmailIsStoredFoldedAndCannotBeAddedTwice() throws Exception {
        UUID personId = personInvitedAt(address("dup"));
        String mixed = "Mixed.Case." + UUID.randomUUID() + "@Smsone.CO.ug";

        add(personId, "EMAIL", mixed, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.value").value(mixed.toLowerCase()));

        add(personId, "EMAIL", mixed.toLowerCase(), null).andExpect(status().isConflict());
    }

    @Test
    void aMalformedAddressAndAnUnknownKindAreBothNamedRatherThanRejectedBlankly() throws Exception {
        UUID personId = personInvitedAt(address("bad"));

        add(personId, "EMAIL", "not-an-address", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/value"));
        add(personId, "EMAII", address("typo"), null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/kind"));
    }

    /**
     * The cap is what lets the list answer without a cursor (ADR 0002 exists to stop unbounded scans,
     * and this collection has a ceiling). If it ever stops being enforced, the unpaginated read becomes
     * the unbounded one.
     */
    @Test
    void theContactBookIsCapped() throws Exception {
        UUID personId = personInvitedAt(address("hoarder"));

        for (int i = 1; i < PersonContacts.MAX_PER_KIND; i++) {
            add(personId, "EMAIL", address("hoard-" + i), null).andExpect(status().isCreated());
        }
        add(personId, "EMAIL", address("one-too-many"), null).andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------------------------------------
    // Proving
    // ---------------------------------------------------------------------------------------------

    /**
     * A token that proves nothing and a token that proves a DIFFERENT mailbox are both refusals, and
     * neither may be a 500 — the second is the ordinary case for anyone who has not yet changed their
     * address at the provider.
     */
    @Test
    void onlyTheAddressTheTokenProvesCanBeVerified() throws Exception {
        UUID personId = personInvitedAt(address("prover"));
        String wanted = address("wanted");
        String contactId = added(personId, "EMAIL", wanted);

        mockMvc.perform(post(CONTACTS + "/" + contactId + "/verification").with(self(personId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));
        prove(personId, address("somewhere-else"), contactId).andExpect(status().isConflict());

        assertThat(rowFor(personId, wanted).get("verified_at")).isNull();
    }

    /**
     * Proving the first address of a kind also makes it primary: the flag provisioning left on an
     * address nobody established is a placeholder, and the first proof retires it. Asserted against the
     * columns because {@code uq_person_contact_primary_live} is what actually holds "exactly one".
     */
    @Test
    void aProofRetiresTheUnprovenPlaceholderAndTakesThePrimaryFlag() throws Exception {
        String invited = address("placeholder");
        UUID personId = personInvitedAt(invited);
        String wanted = address("real");
        String contactId = added(personId, "EMAIL", wanted);

        prove(personId, wanted, contactId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.verified").value(true))
                .andExpect(jsonPath("$.data.attributes.primary").value(true));

        assertThat(rowFor(personId, wanted).get("verified_at")).isNotNull();
        assertThat(rowFor(personId, invited).get("is_primary")).isEqualTo(false);
        assertThat(primaryCount(personId)).isOne();
    }

    /**
     * V10's promise, kept: unverified duplicates are legal, and the collision surfaces AT VERIFICATION.
     * Adding must stay a 201 — a 409 there would answer "does this address have an account here?" for
     * anyone with a login — and the unique index must arrive as a 409, never as a 500 at flush.
     */
    @Test
    void anAddressAnotherAccountHasProvenIsRefusedAtVerificationNotAtAdd() throws Exception {
        String contested = address("contested");
        UUID holder = personVerifiedAt(contested);
        UUID challenger = personInvitedAt(address("challenger"));

        String contactId = added(challenger, "EMAIL", contested);
        prove(challenger, contested, contactId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));

        assertThat(directory.findPersonIdByEmail(contested)).contains(holder);
    }

    // ---------------------------------------------------------------------------------------------
    // Choosing a primary
    // ---------------------------------------------------------------------------------------------

    /**
     * The rule that keeps the primary flag meaningful. A refusal must also leave the incumbent standing:
     * a 409 that had already stood the old primary down would be a rejection that changed something.
     */
    @Test
    void anUnprovenAddressCannotBecomeThePrimaryOne() throws Exception {
        String invited = address("incumbent");
        UUID personId = personVerifiedAt(invited);
        String contactId = added(personId, "EMAIL", address("unproven"));

        mockMvc.perform(put(CONTACTS + "/" + contactId + "/primary").with(self(personId)))
                .andExpect(status().isConflict());

        assertThat(rowFor(personId, invited).get("is_primary")).isEqualTo(true);
        assertThat(primaryCount(personId)).isOne();
    }

    /**
     * The swap. Two live rows may never both hold the flag, so this is also the test that fails if the
     * flush between standing one down and promoting the other is ever removed.
     */
    @Test
    void choosingAPrimaryMovesTheFlagAndLeavesExactlyOne() throws Exception {
        String first = address("first");
        UUID personId = personVerifiedAt(first);
        String second = address("second");
        String contactId = added(personId, "EMAIL", second);
        prove(personId, second, contactId).andExpect(status().isOk());

        // The proof did NOT steal the flag this time — the incumbent was proven, so it was a choice.
        assertThat(rowFor(personId, first).get("is_primary")).isEqualTo(true);

        mockMvc.perform(put(CONTACTS + "/" + contactId + "/primary").with(self(personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.primary").value(true));

        assertThat(rowFor(personId, second).get("is_primary")).isEqualTo(true);
        assertThat(rowFor(personId, first).get("is_primary")).isEqualTo(false);
        assertThat(primaryCount(personId)).isOne();
    }

    // ---------------------------------------------------------------------------------------------
    // Removing
    // ---------------------------------------------------------------------------------------------

    /**
     * Three ways to strand a person, three refusals. They are genuinely different failures: the first
     * leaves nothing to mail, the second leaves nothing to resolve them by, and the third leaves only an
     * address nobody established — which {@code BEST_FIRST} would then start sending to, making
     * "add a claim, delete the rest" a way to point this platform's mail at a stranger.
     */
    @Test
    void aRemovalThatWouldStrandThePersonIsRefused() throws Exception {
        String only = address("only");
        UUID lonely = personVerifiedAt(only);
        remove(lonely, contactId(lonely, only)).andExpect(status().isConflict());

        // Only VERIFIED address, with an unproven one alongside: still unresolvable if it goes.
        UUID resolvable = personVerifiedAt(address("resolvable"));
        added(resolvable, "EMAIL", address("spare"));
        remove(resolvable, contactId(resolvable, addressOfPrimary(resolvable)))
                .andExpect(status().isConflict());

        // Unproven primary (an invite) plus an unproven claim: dropping the primary would leave the
        // claim as the best address on file, which is exactly the fallback that must never happen.
        String invited = address("invited-primary");
        UUID unproven = personInvitedAt(invited);
        added(unproven, "EMAIL", address("claim"));
        remove(unproven, contactId(unproven, invited)).andExpect(status().isConflict());

        assertThat(rows(lonely)).hasSize(1);
    }

    @Test
    void removingThePrimaryPromotesTheBestProvenSurvivor() throws Exception {
        String old = address("old");
        UUID personId = personVerifiedAt(old);
        String replacement = address("replacement");
        String contactId = added(personId, "EMAIL", replacement);
        prove(personId, replacement, contactId).andExpect(status().isOk());

        remove(personId, contactId(personId, old)).andExpect(status().isNoContent());

        assertThat(rows(personId)).hasSize(1);
        assertThat(rowFor(personId, replacement).get("is_primary")).isEqualTo(true);
        assertThat(primaryCount(personId)).isOne();
        // Soft delete, so the row survives with deleted_at set — and the partial unique index it sat in
        // must have released the address.
        Integer deleted = jdbc.queryForObject(
                "select count(*) from person_contact where person_id = ? and deleted_at is not null",
                Integer.class, personId);
        assertThat(deleted).isOne();
    }

    /** A phone number reaches nothing and resolves nobody, so it carries none of the guards. */
    @Test
    void aPhoneNumberIsAPlainRecordAndComesAndGoesFreely() throws Exception {
        UUID personId = personVerifiedAt(address("phone-owner"));

        String contactId = idOf(add(personId, "PHONE", "+256700000000", "mobile")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.verified").value(false)));

        // No proof channel exists for a phone, so it can never be primary — the door is simply locked.
        mockMvc.perform(put(CONTACTS + "/" + contactId + "/primary").with(self(personId)))
                .andExpect(status().isConflict());
        remove(personId, contactId).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------------------------------------
    // Reach
    // ---------------------------------------------------------------------------------------------

    /** Somebody else's contact is a 404, not a 403 — the two answers differ, and 404 tells them less. */
    @Test
    void anotherPersonsContactIsNotReachable() throws Exception {
        String theirs = address("theirs");
        UUID owner = personVerifiedAt(theirs);
        UUID stranger = personVerifiedAt(address("stranger"));
        String contactId = contactId(owner, theirs);

        mockMvc.perform(put(CONTACTS + "/" + contactId + "/primary").with(self(stranger)))
                .andExpect(status().isNotFound());
        remove(stranger, contactId).andExpect(status().isNotFound());
        mockMvc.perform(get(CONTACTS).with(self(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        assertThat(rows(owner)).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Unique per call: one Postgres container serves the whole suite, so rows outlive the test that
     * wrote them and a fixed address would let one class's leftovers decide another's result — which for
     * a table with a GLOBAL unique index on verified addresses is not hypothetical.
     */
    private static String address(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@smsone.co.ug";
    }

    /** What a provisioning invite leaves behind: an ACTIVE person, primary address, nothing proven. */
    private UUID personInvitedAt(String email) {
        return person(email, false);
    }

    /** The same person one proof later. */
    private UUID personVerifiedAt(String email) {
        return person(email, true);
    }

    private UUID person(String email, boolean verified) {
        UUID personId = UUID.randomUUID();
        jdbc.update("""
                insert into person (id, status, invited_at, activated_at, version, created_at)
                values (?, 'ACTIVE', now(), now(), 0, now())
                """, personId);
        EdgeSeed.link(jdbc, personId, EdgeSeed.subjectFor(personId));
        // Two statements rather than a nullable bind: a null timestamptz parameter with no declared type
        // is the one JdbcTemplate call Postgres refuses to infer.
        jdbc.update(verified
                ? """
                  insert into person_contact (id, person_id, kind, contact_value, is_primary, verified_at,
                                              version, created_at)
                  values (?, ?, 'EMAIL', ?, true, now(), 0, now())
                  """
                : """
                  insert into person_contact (id, person_id, kind, contact_value, is_primary, verified_at,
                                              version, created_at)
                  values (?, ?, 'EMAIL', ?, true, null, 0, now())
                  """, UUID.randomUUID(), personId, email);
        return personId;
    }

    /** A plain authenticated person: their linked subject, their issuer, and no proof of any mailbox. */
    private static JwtRequestPostProcessor self(UUID personId) {
        return jwt().jwt(token -> token.subject(EdgeSeed.subjectFor(personId))
                .claim("iss", EdgeSeed.ISSUER));
    }

    /**
     * The same caller, with the identity provider vouching for one address. This is the ONLY proof the
     * platform accepts today, and the claim is spelled exactly as Keycloak spells it.
     */
    private static JwtRequestPostProcessor proving(UUID personId, String email) {
        return jwt().jwt(token -> token.subject(EdgeSeed.subjectFor(personId))
                .claim("iss", EdgeSeed.ISSUER)
                .claim("email", email)
                .claim("email_verified", true));
    }

    private ResultActions add(UUID personId, String kind, String value, String label) throws Exception {
        String body = label == null
                ? "{\"kind\":\"" + kind + "\",\"value\":\"" + value + "\"}"
                : "{\"kind\":\"" + kind + "\",\"value\":\"" + value + "\",\"label\":\"" + label + "\"}";
        return mockMvc.perform(post(CONTACTS).with(self(personId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Adds and returns the new contact's id. Unchecked so it can be used as a fixture in-line. */
    private String added(UUID personId, String kind, String value) {
        try {
            return idOf(add(personId, kind, value, null).andExpect(status().isCreated()));
        } catch (Exception failure) {
            throw new IllegalStateException("could not seed a contact", failure);
        }
    }

    private ResultActions prove(UUID personId, String provenAddress, String contactId) throws Exception {
        return mockMvc.perform(post(CONTACTS + "/" + contactId + "/verification")
                .with(proving(personId, provenAddress)));
    }

    private ResultActions remove(UUID personId, String contactId) throws Exception {
        return mockMvc.perform(delete(CONTACTS + "/" + contactId).with(self(personId)));
    }

    /** The {@code data.id} of a single-resource response. */
    private static String idOf(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        int idAt = body.indexOf("\"id\":\"") + 6;
        return body.substring(idAt, body.indexOf('"', idAt));
    }

    private String contactId(UUID personId, String value) {
        return jdbc.queryForObject(
                "select id from person_contact where person_id = ? and lower(contact_value) = lower(?) "
                + "and deleted_at is null", String.class, personId, value);
    }

    private String addressOfPrimary(UUID personId) {
        return jdbc.queryForObject("select contact_value from person_contact where person_id = ? "
                + "and is_primary and deleted_at is null", String.class, personId);
    }

    private List<Map<String, Object>> rows(UUID personId) {
        return jdbc.queryForList(
                "select * from person_contact where person_id = ? and deleted_at is null", personId);
    }

    private Map<String, Object> rowFor(UUID personId, String value) {
        return jdbc.queryForMap("select * from person_contact where person_id = ? "
                + "and lower(contact_value) = lower(?) and deleted_at is null", personId, value);
    }

    private int primaryCount(UUID personId) {
        Integer count = jdbc.queryForObject("select count(*) from person_contact where person_id = ? "
                + "and is_primary and deleted_at is null", Integer.class, personId);
        return count == null ? 0 : count;
    }
}
