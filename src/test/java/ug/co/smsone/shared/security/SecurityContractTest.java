package ug.co.smsone.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.web.RequestIdFilter;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

@AutoConfigureMockMvc
class SecurityContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestGets401Envelope() throws Exception {
        mockMvc.perform(get("/test/echo"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.errors[0].status").value("401"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void healthProbesArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }
}
