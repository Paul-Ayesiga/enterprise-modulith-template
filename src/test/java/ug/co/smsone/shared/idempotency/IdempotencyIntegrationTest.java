package ug.co.smsone.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

@AutoConfigureMockMvc
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockHttpServletRequestBuilder adminPut(String settingKey, String value, String idemKey) {
        return put("/api/v1/settings/" + settingKey)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header(IdempotencyFilter.KEY_HEADER, idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + value + "\"}");
    }

    @Test
    void duplicateRequestIsReplayedWithoutReexecuting() throws Exception {
        MvcResult first = mockMvc.perform(adminPut("idem.probe", "one", "key-replay-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER))
                .andReturn();

        MvcResult second = mockMvc.perform(adminPut("idem.probe", "one", "key-replay-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                .andReturn();

        // identical stored body (including the original requestId in meta), and no second write
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        Long version = jdbcTemplate.queryForObject(
                "select version from setting where setting_key = 'idem.probe'", Long.class);
        assertThat(version).isZero();
    }

    @Test
    void sameKeyDifferentPayloadConflicts() throws Exception {
        mockMvc.perform(adminPut("idem.conflict", "one", "key-conflict-1"))
                .andExpect(status().isOk());

        mockMvc.perform(adminPut("idem.conflict", "CHANGED", "key-conflict-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"))
                .andExpect(jsonPath("$.errors[0].source.header").value(IdempotencyFilter.KEY_HEADER));
    }

    @Test
    void malformedKeyIsRejected() throws Exception {
        mockMvc.perform(adminPut("idem.badkey", "x", "not valid!!"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].source.header").value(IdempotencyFilter.KEY_HEADER));
    }

    @Test
    void distinctKeysExecuteIndependently() throws Exception {
        mockMvc.perform(adminPut("idem.distinct", "v1", "key-distinct-1"))
                .andExpect(status().isOk());
        mockMvc.perform(adminPut("idem.distinct", "v2", "key-distinct-2"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER))
                .andExpect(jsonPath("$.data.attributes.value").value("v2"));

        Long version = jdbcTemplate.queryForObject(
                "select version from setting where setting_key = 'idem.distinct'", Long.class);
        assertThat(version).isEqualTo(1L);
    }
}
