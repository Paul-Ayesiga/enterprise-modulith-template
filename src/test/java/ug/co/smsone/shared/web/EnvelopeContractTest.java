package ug.co.smsone.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnvelopeContractTest {

    private static final String ULID_PATTERN = "[0-9A-HJKMNP-TV-Z]{26}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void wrapsSuccessInEnvelopeWithRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/echo"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.data.message").value("hello"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId", Matchers.matchesPattern(ULID_PATTERN)))
                .andExpect(jsonPath("$.meta.timestamp").exists())
                .andExpect(jsonPath("$.meta.apiVersion").value("1"))
                .andExpect(jsonPath("$.links.self").value("/test/echo"))
                .andReturn();
        String headerRequestId = result.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(result.getResponse().getContentAsString()).contains("\"requestId\":\"" + headerRequestId + "\"");
    }

    @Test
    void echoesValidInboundRequestId() throws Exception {
        mockMvc.perform(get("/test/echo").header(RequestIdFilter.REQUEST_ID_HEADER, "client-id_123"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "client-id_123"))
                .andExpect(jsonPath("$.meta.requestId").value("client-id_123"));
    }

    @Test
    void replacesMalformedInboundRequestId() throws Exception {
        mockMvc.perform(get("/test/echo").header(RequestIdFilter.REQUEST_ID_HEADER, "bad id \n<script>"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, Matchers.matchesPattern(ULID_PATTERN)));
    }

    @Test
    void returnsMultiErrorEnvelopeForValidationFailure() throws Exception {
        mockMvc.perform(post("/test/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"name\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.errors[*].status", Matchers.everyItem(Matchers.is("422"))))
                .andExpect(jsonPath("$.errors[*].code", Matchers.everyItem(Matchers.startsWith("VALIDATION_"))))
                .andExpect(jsonPath("$.errors[*].source.pointer",
                        Matchers.hasItems("/data/attributes/email", "/data/attributes/name")))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void translatesBusinessExceptionToEnvelope() throws Exception {
        mockMvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].status").value("404"))
                .andExpect(jsonPath("$.errors[0].detail").value("Thing 42 does not exist."))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void translatesFrameworkErrorsToEnvelope() throws Exception {
        mockMvc.perform(get("/no/such/resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.meta.requestId").exists());

        mockMvc.perform(post("/test/echo"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errors[0].code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void neverLeaksStackTracesOrInternalDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errors[0].code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errors[0].status").value("500"))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("at ug.")
                .doesNotContain("secret-internal-detail")
                .doesNotContain("IllegalState")
                .doesNotContain("stackTrace");
    }
}
