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
        return adminPutAs("admin-alice", settingKey, value, idemKey);
    }

    private MockHttpServletRequestBuilder adminPutAs(String subject, String settingKey, String value,
            String idemKey) {
        return put("/api/v1/settings/" + settingKey)
                .with(jwt().jwt(token -> token.subject(subject))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
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

        // Byte-identical stored body: the replayed body still carries the FIRST request's
        // requestId, while the replay's own X-Request-Id header is a fresh id. A re-executed
        // handler would have minted its new requestId into the body — this proves it did not run.
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        String firstBodyRequestId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.meta.requestId");
        assertThat(second.getResponse().getContentAsString()).contains(firstBodyRequestId);
        assertThat(second.getResponse().getHeader("X-Request-Id")).isNotEqualTo(firstBodyRequestId);

        Long version = jdbcTemplate.queryForObject(
                "select version from setting where setting_key = 'idem.probe'", Long.class);
        assertThat(version).isZero();
    }

    @Test
    void keysAreScopedPerPrincipal() throws Exception {
        // alice completes with the key
        mockMvc.perform(adminPutAs("alice", "idem.scoped", "alice-value", "shared-key-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

        // bob reuses the SAME key with the SAME payload — he must execute independently,
        // never receive alice's stored response
        mockMvc.perform(adminPutAs("bob", "idem.scoped", "alice-value", "shared-key-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

        // and bob using the key with a DIFFERENT payload is also not blocked by alice's claim
        mockMvc.perform(adminPutAs("bob", "idem.scoped2", "bob-value", "shared-key-2"))
                .andExpect(status().isOk());
        mockMvc.perform(adminPutAs("alice", "idem.scoped3", "other", "shared-key-2"))
                .andExpect(status().isOk());
    }

    @Test
    void oversizedBodyIsRejectedNotBuffered() throws Exception {
        String bigValue = "x".repeat(300_000); // above the 256KiB default cap
        mockMvc.perform(adminPut("idem.big", bigValue, "key-big-1"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errors[0].code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void errorResponsesAreNotStored() throws Exception {
        // 422 (blank value) with a key: outcome is an error -> the key stays free
        mockMvc.perform(adminPut("idem.errfree", "", "key-err-1"))
                .andExpect(status().isUnprocessableEntity());

        // the same key retries successfully — no replay of the stored error, no conflict
        mockMvc.perform(adminPut("idem.errfree", "recovered", "key-err-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER))
                .andExpect(jsonPath("$.data.attributes.value").value("recovered"));
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
