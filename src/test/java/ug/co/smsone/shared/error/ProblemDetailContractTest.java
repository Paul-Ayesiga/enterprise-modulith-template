package ug.co.smsone.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/** RFC 9457 content negotiation: same errors, problem+json shape on request. */
@AutoConfigureMockMvc
class ProblemDetailContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void problemJsonIsReturnedWhenRequested() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/missing").with(jwt())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("Thing 42 does not exist."))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();
        // problem+json, not the envelope
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"meta\"");
    }

    @Test
    void validationProblemCarriesErrorsExtension() throws Exception {
        mockMvc.perform(post("/test/signup").with(jwt())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nope\",\"name\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void singleValidationErrorKeepsItsSourcePointer() throws Exception {
        mockMvc.perform(post("/test/signup").with(jwt())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ok@smsone.co.ug\",\"name\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/name"));
    }

    @Test
    void catchAll500NeverLeaksInProblemJsonEither() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/boom").with(jwt())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Exception")
                .doesNotContain("at ug.")
                .doesNotContain("secret-internal-detail")
                .doesNotContain("IllegalState");
    }

    @Test
    void securityErrorsNegotiateProblemJsonToo() throws Exception {
        mockMvc.perform(get("/api/v1/settings")
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void defaultAcceptStillGetsTheEnvelope() throws Exception {
        mockMvc.perform(get("/test/missing").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }
}
