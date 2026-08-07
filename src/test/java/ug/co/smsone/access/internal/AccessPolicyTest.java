package ug.co.smsone.access.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * Devices self-serve and register idempotently; the org security policy enforces its four rules
 * through the REAL filter — an IP outside the allowlist, an over-age token, a call with no trusted
 * device, and a single-factor session under require-MFA each get a policy-named 403, while a
 * compliant call passes. Trust is the org's grant, and a policy denial names its rule so it never
 * reads as RBAC.
 */
@AutoConfigureMockMvc
class AccessPolicyTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void devicesRegisterIdempotentlyAndRevoke() throws Exception {
        // A device belongs to a PERSON, so the token has to resolve to one: subject linked in
        // external_identity, issuer byte-identical to what the resource server validated.
        String subject = "dev-owner-" + UUID.randomUUID();
        EdgeSeed.person(jdbc, subject);
        var me = jwt().jwt(t -> t.claim("iss", EdgeSeed.ISSUER).subject(subject));
        MvcResult first = mockMvc.perform(post("/api/v1/me/devices").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Laptop\",\"kind\":\"browser\",\"fingerprint\":\"fp-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.kind").value("BROWSER"))
                .andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.data.id");

        // Same fingerprint re-registers, not duplicates.
        mockMvc.perform(post("/api/v1/me/devices").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Laptop (work)\",\"kind\":\"BROWSER\",\"fingerprint\":\"fp-1\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/me/devices").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.name").value("Laptop (work)"));

        mockMvc.perform(delete("/api/v1/me/devices/{id}", id).with(me)).andExpect(status().isNoContent());
    }

    @Test
    void theThreePolicyRulesEachDenyWithTheirName() throws Exception {
        UUID orgId = seedOrg();
        Member policed = seedMember(orgId, "policed-1", "ORG_READ", "ORG_UPDATE", "SUBSCRIPTION_READ");

        // IP allowlist: MockMvc's remote addr is 127.0.0.1, so an allowlist of 10.0.0.0/8 blocks it.
        setPolicy(orgId, policed, "{\"ipAllowlist\":\"10.0.0.0/8\",\"requireTrustedDevice\":false}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(policed.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("ip-allowlist")));
        // 127.0.0.1 in the allowlist → passes.
        setPolicy(orgId, policed, "{\"ipAllowlist\":\"127.0.0.0/8\",\"requireTrustedDevice\":false}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(policed.token()))
                .andExpect(status().isOk());

        // Session max age: a token issued an hour ago is refused when the cap is 60s.
        setPolicy(orgId, policed, "{\"requireTrustedDevice\":false,\"sessionMaxAgeSeconds\":60}");
        var stale = jwt().jwt(t -> t.claim("iss", EdgeSeed.ISSUER).subject(policed.subject())
                .issuedAt(Instant.now().minusSeconds(3600))
                .claim("organization", orgClaim(orgId)));
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(stale))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("session-max-age")));

        // Trusted device: no X-Device-Id → blocked; blessed device present → passes.
        setPolicy(orgId, policed, "{\"requireTrustedDevice\":true}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(policed.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("trusted-device")));

        // register a device, have the org bless it, then present it. The org blesses it for the
        // PERSON who registered it — trust hangs off person_id now, not off a subject string.
        MvcResult device = mockMvc.perform(post("/api/v1/me/devices").with(policed.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phone\",\"kind\":\"MOBILE\",\"fingerprint\":\"trusted-fp\"}"))
                .andExpect(status().isCreated()).andReturn();
        String deviceId = JsonPath.read(device.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(post("/api/v1/orgs/{orgId}/security-policy/trusted-devices", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + policed.personId() + "\",\"deviceId\":\"" + deviceId
                                + "\",\"trusted\":true}")
                        .with(policed.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Device-Id", "trusted-fp").with(policed.token()))
                .andExpect(status().isOk());

        // The device's last_seen_at was stamped by the enforcement filter.
        assertThat(jdbc.queryForObject(
                "select count(*) from user_device where fingerprint = 'trusted-fp' and last_seen_at is not null",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void mfaRuleRefusesSingleFactorSessionsButExemptsThePolicyEndpoint() throws Exception {
        UUID orgId = seedOrg();
        Member policed = seedMember(orgId, "policed-mfa", "ORG_READ", "ORG_UPDATE", "SUBSCRIPTION_READ");
        setPolicy(orgId, policed, "{\"requireTrustedDevice\":false,\"requireMfa\":true}");

        // Single-factor session (no amr claim): refused, naming the rule.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(policed.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("mfa")));

        // A session that carried a second factor (amr lists otp) passes — the SAME person, so the
        // only difference between the two calls is the factor the session carried.
        var mfa = jwt().jwt(t -> t.claim("iss", EdgeSeed.ISSUER).subject(policed.subject())
                .claim("amr", java.util.List.of("pwd", "otp"))
                .claim("organization", orgClaim(orgId)));
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(mfa))
                .andExpect(status().isOk());

        // Recovery hatch: the policy's own surface stays reachable to loosen the rule.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/security-policy", orgId).with(policed.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.requireMfa").value(true));
    }

    /**
     * An organization may only bless the devices of its OWN members.
     *
     * <p>{@code setDeviceTrust} takes the {@code orgId} from the path — which the permission check
     * gates — and the {@code personId} from the request BODY, which nothing checked against it:
     * {@code ApiPermissionEvaluator} only ever compares {@code #orgId} and never sees the person you
     * named. So an {@code org:update} holder anywhere could flip trust on any person's device.
     *
     * <p>The refusal is a 404 and not a 403 on purpose: an org with no business touching this person
     * must not be able to tell a non-member from a person who does not exist.
     */
    @Test
    void anOrgCannotBlessADeviceBelongingToSomeoneElsesMember() throws Exception {
        UUID attackerOrg = seedOrg();
        Member attacker = seedMember(attackerOrg, "attacker", "ORG_READ", "ORG_UPDATE");

        UUID victimOrg = seedOrg();
        Member victim = seedMember(victimOrg, "victim", "ORG_READ");
        UUID victimDevice = registerDevice(victim, "victim-fp");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/security-policy/trusted-devices", attackerOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + victim.personId() + "\",\"deviceId\":\""
                                + victimDevice + "\",\"trusted\":true}")
                        .with(attacker.token()))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from user_device_trust where device_id = ?",
                Integer.class, victimDevice))
                .as("no grant may be written for a person who is not the acting org's member")
                .isZero();
    }

    /**
     * Trust granted by one organization must not satisfy another's {@code require_trusted_device}.
     *
     * <p>V31 recorded this as a known modelling bug and deferred it: {@code user_device.trusted} was a
     * single global boolean while the policy that reads it is per-org. Combined with open self-signup —
     * where a new org's creator is OWNER with every permission — the bypass needed no guessed
     * identifiers at all: make your own org, bless your own device with your own ids, and walk through
     * a different tenant's device policy. V51 moves the grant onto {@code (device, org)}.
     */
    @Test
    void aDeviceTrustedInOneOrgDoesNotSatisfyAnothersPolicy() throws Exception {
        // One person, two orgs — the only shape in which the old global flag could leak.
        UUID orgA = seedOrg();
        Member inA = seedMember(orgA, "dual", "ORG_READ", "ORG_UPDATE");
        UUID orgB = seedOrg();
        UUID roleB = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, 'POLB', 'PolRole', false, 0, now())", roleB, orgB);
        for (String permission : new String[] {"ORG_READ", "ORG_UPDATE", "SUBSCRIPTION_READ"}) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleB, permission);
        }
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgB, inA.personId(), roleB);

        UUID device = registerDevice(inA, "dual-fp");

        // Org A blesses it — legitimately, for org A.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/security-policy/trusted-devices", orgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + inA.personId() + "\",\"deviceId\":\"" + device
                                + "\",\"trusted\":true}")
                        .with(inA.token()))
                .andExpect(status().isOk());

        // Org B requires trusted devices and never blessed anything.
        setPolicy(orgB, new Member(inA.personId(), inA.subject(), member(orgB, inA.subject())),
                "{\"requireTrustedDevice\":true}");

        // Asserted on a NORMAL endpoint: /security-policy is deliberately exempt from the device rule
        // (the same exemption the MFA test relies on) so an org cannot lock itself out of its own policy.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgB)
                        .header("X-Device-Id", "dual-fp").with(member(orgB, inA.subject())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail",
                        org.hamcrest.Matchers.containsString("trusted-device")));

        assertThat(jdbc.queryForObject(
                "select count(*) from user_device_trust where device_id = ? and org_id = ?",
                Integer.class, device, orgA))
                .as("org A's own grant is untouched — this is isolation, not revocation")
                .isEqualTo(1);
    }

    /** Registers a device for this member and returns its id. */
    private UUID registerDevice(Member member, String fingerprint) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/me/devices").with(member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phone\",\"kind\":\"MOBILE\",\"fingerprint\":\""
                                + fingerprint + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.id"));
    }

    private void setPolicy(UUID orgId, Member member, String body) throws Exception {
        mockMvc.perform(put("/api/v1/orgs/{orgId}/security-policy", orgId)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(member.token()))
                .andExpect(status().isOk());
    }

    /** A seeded org member: the person the edge resolves to, their subject, and their token. */
    private record Member(UUID personId, String subject, RequestPostProcessor token) {
    }

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * A person holding {@code permissions} in {@code orgId}: the {@code external_identity} link the
     * token resolves through, a role carrying the codes, and the membership joining them.
     */
    private Member seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'PolRole', false, 0, now())", roleId, orgId,
                "POL_" + roleId.toString().substring(0, 8).toUpperCase());
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        return new Member(personId, subject, member(orgId, subject));
    }

    /**
     * A token for a seeded person: {@code iss} must be {@link EdgeSeed#ISSUER} byte-for-byte or the
     * (issuer, subject) pair resolves to no person, and the alias-keyed {@code organization} claim
     * carries the PROVIDER's org id — both rebuilt from what {@link EdgeSeed} wrote.
     */
    private RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.claim("iss", EdgeSeed.ISSUER).subject(subject)
                .claim("organization", orgClaim(orgId)));
    }

    /** The alias-keyed {@code organization} claim Keycloak mints, rebuilt from the seeded link. */
    private Map<String, Object> orgClaim(UUID orgId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return Map.of(String.valueOf(link.get("external_alias")),
                Map.of("id", String.valueOf(link.get("external_org_id"))));
    }
}
