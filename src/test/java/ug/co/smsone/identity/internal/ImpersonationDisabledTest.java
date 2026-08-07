package ug.co.smsone.identity.internal;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The kill switch REFUSES; it does not make the feature vanish, and it does not quietly ignore the
 * header. Both halves matter.
 *
 * <p>A 404 on the route would be indistinguishable from a typo'd path or a version skew, sending an
 * operator to debug their client instead of reading the one answer that explains it.
 *
 * <p>Ignoring {@code X-Impersonate} is the more dangerous half, which is why the second test asserts a
 * refusal rather than a pass-through: a silently-dropped header means the request SUCCEEDS as the
 * operator themselves while they believe they are wearing the target — they read their own data
 * thinking it is the customer's, and any write lands under the wrong identity with nothing in the
 * trail recording that the header was ever sent.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.impersonation.enabled=false")
class ImpersonationDisabledTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** A superadmin gets 201 here when the feature is on ({@link ImpersonationApiTest}), so the 403 is the switch. */
    @Test
    void theRouteRefusesInsteadOfDisappearing() throws Exception {
        mockMvc.perform(post("/api/v1/admin/impersonations").with(operator(PlatformRole.SUPERADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPersonId\":\"" + UUID.randomUUID()
                                + "\",\"reason\":\"ticket 4711 refund missing\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].detail").value(containsString("disabled")));
    }

    @Test
    void theListingAndTheEndpointThatEndsASessionRefuseToo() throws Exception {
        mockMvc.perform(get("/api/v1/admin/impersonations").with(operator(PlatformRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/admin/impersonations/{id}", UUID.randomUUID())
                        .with(operator(PlatformRole.ADMIN)))
                .andExpect(status().isForbidden());
    }

    /**
     * The request must NOT succeed as the operator. Asserting 403 rather than "not 200" is deliberate:
     * this exact call returns 200 without the header, so a pass-through would look like success.
     */
    @Test
    void theImpersonateHeaderIsRefusedRatherThanIgnored() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").with(operator(PlatformRole.SUPPORT))
                        .header("X-Impersonate", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail").value(containsString("disabled")));

        // Control: the same call without the header is the 200 the assertion above is distinguished from.
        mockMvc.perform(get("/api/v1/admin/users").with(operator(PlatformRole.SUPPORT)))
                .andExpect(status().isOk());
    }

    /**
     * The switch is checked before anything else, so this operator needs no person row: the 403 under
     * test must arrive whether or not the caller could otherwise have opened a session.
     */
    private static JwtRequestPostProcessor operator(String role) {
        return ImpersonationFixtures.operator(UUID.randomUUID(), role);
    }
}
