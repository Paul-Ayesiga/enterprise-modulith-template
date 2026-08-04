package ug.co.smsone.signup.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/** The default posture: signup OFF answers a named 403, not a 404 that looks like a typo'd path. */
@AutoConfigureMockMvc
class SignupDisabledTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void disabledSignupIsANamedRefusal() throws Exception {
        mockMvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Acme\",\"email\":\"a@b.test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail").value(
                        org.hamcrest.Matchers.containsString("SIGNUP_ENABLED")));
    }
}
