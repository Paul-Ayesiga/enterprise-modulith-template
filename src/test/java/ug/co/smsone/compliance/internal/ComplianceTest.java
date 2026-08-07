package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.security.PlatformAdmins;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The REST-level compliance guarantees: consent is append-only; an erasure soft-deletes a data
 * subject's rows; a legal hold makes an erasure REFUSED; the active-hold listing is a keyset page
 * rather than the whole collection. The purge-survival half (a held row outlives the purge) lives in
 * {@code scheduler.internal.LegalHoldPurgeTest}, where the package-private purge job is reachable.
 *
 * <p>Every caller here is seeded as a {@code person} with an {@code external_identity} link, because
 * that link is now what a token resolves through — a bare {@code jwt()} subject reaches no person, and
 * these endpoints refuse a caller who is not one.
 */
@AutoConfigureMockMvc
class ComplianceTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // The role data lives in Keycloak; stub the port so the guard's refusal is tested without a live IdP.
    @MockitoBean
    private PlatformAdmins platformAdmins;

    @Test
    void consentIsAppendOnlyAndErasureSoftDeletes() throws Exception {
        UUID personId = seedPerson();
        var me = tokenFor(personId);

        mockMvc.perform(post("/api/v1/me/consents").with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"marketing\",\"granted\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/me/consents").with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"marketing\",\"granted\":false}"))
                .andExpect(status().isOk());
        // Both rows survive — a withdrawal is a new record, not an overwrite.
        mockMvc.perform(get("/api/v1/me/consents").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(post("/api/v1/me/erasure-request").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("EXECUTED"));
        assertThat(livePeople(personId)).as("erasure soft-deleted the person").isZero();
        // The link goes too, and that is the point rather than tidiness: uq_external_identity_subject_live
        // is partial on deleted_at, so a live link left behind is an erased account that still signs in.
        assertThat(jdbc.queryForObject(
                "select count(*) from external_identity where person_id = ? and deleted_at is null",
                Integer.class, personId)).as("an erased person cannot still authenticate").isZero();
    }

    @Test
    void aLegalHoldMakesErasureRefused() throws Exception {
        UUID personId = seedPerson();
        var operator = admin(); // one accountable human across both acts, as a real hold-then-erase is
        mockMvc.perform(post("/api/v1/admin/compliance/legal-holds/subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + personId + "\",\"reason\":\"litigation #99\"}")
                        .with(operator))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/compliance/erasure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + personId + "\"}").with(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("REFUSED"));
        // Refused erasure means the person was NOT soft-deleted.
        assertThat(livePeople(personId)).isEqualTo(1);
    }

    @Test
    void erasingTheLastPlatformSuperAdminIsRefused() throws Exception {
        UUID personId = seedPerson();
        given(platformAdmins.isSoleSuperAdmin(personId)).willReturn(true);

        mockMvc.perform(post("/api/v1/me/erasure-request").with(tokenFor(personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("REFUSED"));
        assertThat(livePeople(personId)).as("the last super-admin must not be erased").isEqualTo(1);
    }

    /**
     * Active holds are a keyset page, not a list (ADR 0002). Holds are never deleted — only released —
     * so the active set only ever grows, and a single litigation sweep can place thousands at once;
     * returning them whole was an unbounded response that also sorted every active row per call.
     *
     * <p>The page seam is what this asserts, and it is the part a naive "just add a limit" gets wrong:
     * the second page must not repeat the first. It says nothing about WHICH holds come back, because
     * the suite shares one database and other tests place holds of their own — the invariant that has
     * to hold regardless is that the cursor walks forward without overlap.
     */
    @Test
    void activeHoldsComeBackOneCursorPageAtATime() throws Exception {
        var operator = admin();
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/v1/admin/compliance/legal-holds/org")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orgId\":\"" + UUID.randomUUID() + "\",\"reason\":\"sweep #" + i + "\"}")
                            .with(operator))
                    .andExpect(status().isCreated());
        }

        var firstPage = mockMvc.perform(get("/api/v1/admin/compliance/legal-holds")
                        .param("page[size]", "2").with(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.page.hasMore").value(true))
                .andExpect(jsonPath("$.meta.page.nextCursor").isNotEmpty())
                .andReturn();

        String body = firstPage.getResponse().getContentAsString();
        List<String> firstIds = JsonPath.read(body, "$.data[*].id");
        String cursor = JsonPath.read(body, "$.meta.page.nextCursor");

        mockMvc.perform(get("/api/v1/admin/compliance/legal-holds")
                        .param("page[size]", "2").param("page[after]", cursor).with(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data[*].id",
                        Matchers.everyItem(Matchers.not(Matchers.in(firstIds)))));
    }

    /**
     * A cursor minted for a DIFFERENT collection is the client's mistake, not a 500 — and the 422 is
     * what proves the sort-validating overload is in use. This cursor is perfectly well-formed; its
     * keys are just {@code createdAt}/{@code id} rather than this collection's {@code placedAt}/{@code
     * id}. {@code page.scrollPosition(SORT)} rejects it at the door; the bare {@code scrollPosition()}
     * would hand it to the keyset query, which fails on a property the sort does not name.
     */
    @Test
    void aCursorMintedForAnotherCollectionIs422() throws Exception {
        String foreignCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("createdAt=t:2020-01-01T00:00:00Z|id=u:" + UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/admin/compliance/legal-holds")
                        .param("page[after]", foreignCursor).with(admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("page[after]"));
    }

    /**
     * A platform operator — and a {@code person}, which is the part V34 made load-bearing.
     * {@code legal_hold.placed_by_person_id} and {@code erasure_request.requested_by_person_id} are NOT
     * NULL, so {@code AdminComplianceController} refuses any caller it cannot name an accountable human
     * for. The realm role alone is no longer a compliance credential.
     */
    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return tokenFor(EdgeSeed.person(jdbc, "kc-compliance-admin-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    private UUID seedPerson() {
        UUID personId = UUID.randomUUID();
        return EdgeSeed.personWithEmail(jdbc, EdgeSeed.subjectFor(personId), personId + "@smsone.co.ug");
    }

    /**
     * A token that resolves to {@code personId}. The {@code iss} claim is load-bearing:
     * {@code external_identity} is keyed on (issuer, subject), so a token without it reaches no person
     * and every endpoint here — each of which acts FOR a person — answers 403.
     */
    private JwtRequestPostProcessor tokenFor(UUID personId) {
        String subject = jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ?", String.class, personId);
        return jwt().jwt(t -> t.subject(subject).claim("iss", EdgeSeed.ISSUER));
    }

    private int livePeople(UUID personId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from person where id = ? and deleted_at is null", Integer.class, personId);
        return count == null ? 0 : count;
    }
}
