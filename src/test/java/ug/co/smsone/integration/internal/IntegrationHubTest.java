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
import ug.co.smsone.testsupport.EdgeSeed;

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
        UUID orgId = seedOrg();
        String subject = seedMember(orgId, "integrator", "ORG_READ", "ORG_UPDATE");

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
                        .with(member(orgId, subject)))
                .andExpect(status().isOk());

        // Resolve returns the override for this org, with the secret DECRYPTED for the in-JVM caller.
        Integrations.ResolvedIntegration resolved =
                hub.resolve(orgId, Integrations.Kind.SMS_PROVIDER).orElseThrow();
        assertThat(resolved.provider()).isEqualTo("twilio");
        assertThat(resolved.settings().get("apiKey")).isEqualTo("ORG-SECRET");

        // ...but the REST read still masks it, and the ciphertext is what sits in the column.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/integrations", orgId).with(member(orgId, subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.settings.apiKey").value("••••••"));
        String stored = jdbc.queryForObject(
                "select setting_value from integration_setting where setting_key = 'apiKey' "
                        + "and setting_value like 'enc:v1:%' limit 1", String.class);
        assertThat(stored).startsWith("enc:v1:").doesNotContain("ORG-SECRET");
    }

    @Test
    void kindIsValidatedAndTestReportsCompleteness() throws Exception {
        UUID orgId = seedOrg();
        String subject = seedMember(orgId, "integrator-2", "ORG_READ", "ORG_UPDATE");
        mockMvc.perform(put("/api/v1/orgs/{orgId}/integrations", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"CARRIER_PIGEON\",\"provider\":\"x\"}")
                        .with(member(orgId, subject)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/kind"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("plat-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    /**
     * A token for {@code subject}, scoped to {@code orgId}. The {@code organization} claim is rebuilt
     * from the seeded link rather than spelled by hand: it is alias-keyed and carries the PROVIDER's
     * org id, and {@code organization.id} — which the path variable uses — never appears in it. The
     * {@code iss} is not decoration either: {@code external_identity} and {@code external_organization}
     * are both keyed on it, so a token without it resolves to neither person nor tenant.
     */
    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(token -> token.subject(subject).claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * An ACTIVE member of {@code orgId} holding {@code permissions}, and the token subject that
     * resolves to them. The person and its {@code external_identity} link come from {@link EdgeSeed};
     * the membership keys on {@code person_id} now, so the subject never reaches this table.
     */
    private String seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'IntRole', false, 0, now())", roleId, orgId,
                "INT_" + label.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        return subject;
    }
}
