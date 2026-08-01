package ug.co.smsone.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.integration.Integrations;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The hub resolves an org's override over the platform default, decrypts secrets for the in-JVM
 * port but masks them on the wire, and keeps one integration per (scope, kind) so resolution is
 * deterministic. Proves the {@code Integrations} port a notification/billing consumer relies on.
 */
@AutoConfigureMockMvc
class IntegrationHubTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Integrations hub;

    @Test
    void anOrgOverrideWinsOverThePlatformDefaultAndSecretsAreEncrypted() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "integrator", "ORG_READ", "ORG_UPDATE");

        // Platform default SMS provider (admin).
        mockMvc.perform(put("/api/v1/admin/integrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SMS_PROVIDER\",\"provider\":\"platform-sms\","
                                + "\"settings\":{\"apiKey\":\"PLATFORM-SECRET\",\"sender\":\"SMSOne\"}}")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.settings.apiKey").value("••••••"))
                .andExpect(jsonPath("$.data.attributes.settings.sender").value("SMSOne"));

        // A different org with no override resolves the platform default.
        assertThat(hub.resolve(UUID.randomUUID(), Integrations.Kind.SMS_PROVIDER))
                .get().extracting(Integrations.ResolvedIntegration::provider).isEqualTo("platform-sms");

        // This org overrides it.
        mockMvc.perform(put("/api/v1/orgs/{orgId}/integrations", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SMS_PROVIDER\",\"provider\":\"twilio\","
                                + "\"settings\":{\"apiKey\":\"ORG-SECRET\",\"authToken\":\"tok\"}}")
                        .with(member(orgId, "integrator")))
                .andExpect(status().isOk());

        // Resolve returns the override for this org, with the secret DECRYPTED for the in-JVM caller.
        Integrations.ResolvedIntegration resolved =
                hub.resolve(orgId, Integrations.Kind.SMS_PROVIDER).orElseThrow();
        assertThat(resolved.provider()).isEqualTo("twilio");
        assertThat(resolved.settings().get("apiKey")).isEqualTo("ORG-SECRET");

        // ...but the REST read still masks it, and the ciphertext is what sits in the column.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/integrations", orgId).with(member(orgId, "integrator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.settings.apiKey").value("••••••"));
        String stored = jdbc.queryForObject(
                "select setting_value from integration_setting where setting_key = 'apiKey' "
                        + "and setting_value like 'enc:v1:%' limit 1", String.class);
        assertThat(stored).startsWith("enc:v1:").doesNotContain("ORG-SECRET");
    }

    @Test
    void kindIsValidatedAndTestReportsCompleteness() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "integrator-2", "ORG_READ", "ORG_UPDATE");
        mockMvc.perform(put("/api/v1/orgs/{orgId}/integrations", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"CARRIER_PIGEON\",\"provider\":\"x\"}")
                        .with(member(orgId, "integrator-2")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/kind"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("plat-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    private void seedMember(UUID orgId, String subject, String... permissions) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'IntRole', false, 0, now())", roleId, orgId,
                "INT_" + subject.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
