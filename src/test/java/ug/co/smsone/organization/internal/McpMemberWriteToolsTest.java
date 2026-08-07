package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProviderOrgMembership;
import ug.co.smsone.mcp.internal.McpTestSupport;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The member WRITE tools through the real MCP loop, from the org package so the Keycloak gateway
 * can be stubbed (the same edge {@code OrgRbacApiTest} mocks). What this pins: an API key's minted
 * permission subset is its held set for the escalation guard — it can hand on roles whose
 * permissions sit inside that subset, nothing more, and a denial happens BEFORE any provisioning
 * side effect.
 *
 * <p>Tenants are seeded through {@link EdgeSeed#organization} rather than as a bare uuid: the
 * provider link is not decoration here. {@code MemberService} resolves it on both write paths —
 * invite REFUSES outright without one (a member attached to nothing would hold access nowhere), and
 * remove needs it to unlink at the provider — so an org without its {@code external_organization}
 * row exercises neither rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpMemberWriteToolsTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private PersonProvisioning personProvisioning;

    @MockitoBean
    private ProviderOrgMembership providerOrgMembership;

    @MockitoBean
    private KeycloakOrgAdminGateway keycloakOrg;

    /**
     * A tenant plus the {@code external_organization} row that names it at the provider — the pair a
     * real org creation always leaves behind. Returns {@code organization.id}, the tenant key the
     * key and every membership below are scoped by.
     */
    private UUID linkedOrg(String prefix) {
        String distinct = prefix + "-" + UUID.randomUUID();
        return EdgeSeed.organization(jdbc, "kc-org-" + distinct, distinct);
    }

    @Test
    void aKeyWithInvitePermissionInvitesWithinItsMintedSubset() {
        UUID orgId = linkedOrg("inv");
        McpTestSupport.seedRole(jdbc, orgId, "MEMBER", "org:read");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "inviter",
                "member:invite", "org:read");
        UUID invited = UUID.randomUUID();
        given(personProvisioning.provision(any()))
                .willReturn(new ProvisionedPerson(invited, "new@acme.test", false));

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("member_invite")
                    .arguments(Map.of("email", "new@acme.test", "role_code", "MEMBER"))
                    .build());
            assertThat(result.isError())
                    .as(McpTestSupport.textOf(result)).isNotEqualTo(Boolean.TRUE);

            // Attaching at the provider goes through identity, which owns the subject the call needs.
            then(providerOrgMembership).should().attach(eq(invited), any());
            Integer memberships = jdbc.queryForObject(
                    "select count(*) from membership where org_id = ? and person_id = ?",
                    Integer.class, orgId, invited);
            assertThat(memberships).isEqualTo(1);
        }
    }

    @Test
    void aKeyCannotGrantARoleBeyondItsMintedSubset() {
        UUID orgId = linkedOrg("esc");
        // The role carries a permission the KEY does not hold — granting it would be escalation.
        McpTestSupport.seedRole(jdbc, orgId, "ADMINISH", "org:read", "member:remove");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "capped",
                "member:invite", "org:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("member_invite")
                    .arguments(Map.of("email", "esc@acme.test", "role_code", "ADMINISH"))
                    .build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("FORBIDDEN").contains("member:remove");
            // The denial ran BEFORE the side effect — nothing was provisioned, nobody was linked.
            then(personProvisioning).should(never()).provision(any());
            then(providerOrgMembership).should(never()).attach(any(), any());
        }
    }

    @Test
    void aKeyWithoutThePermissionNeverReachesProvisioning() {
        UUID orgId = linkedOrg("den");
        McpTestSupport.seedRole(jdbc, orgId, "MEMBER", "org:read");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "reader", "org:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("member_invite")
                    .arguments(Map.of("email", "no@acme.test", "role_code", "MEMBER"))
                    .build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("member:invite");
            then(personProvisioning).should(never()).provision(any());
        }
    }

    @Test
    void memberRemoveEndsTheMembershipAndUnlinksKeycloak() {
        UUID orgId = linkedOrg("rem");
        // An OWNER member must remain — remove() protects the last owner; the target is a MEMBER.
        UUID ownerRole = McpTestSupport.seedRole(jdbc, orgId, "OWNER", "org:read", "member:remove");
        UUID memberRole = McpTestSupport.seedRole(jdbc, orgId, "MEMBER", "org:read");
        UUID owner = EdgeSeed.person(jdbc, "owner-" + UUID.randomUUID());
        UUID gone = EdgeSeed.person(jdbc, "leaver-" + UUID.randomUUID());
        McpTestSupport.seedMembership(jdbc, orgId, owner, ownerRole);
        McpTestSupport.seedMembership(jdbc, orgId, gone, memberRole);
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "remover",
                "member:remove", "org:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("member_remove")
                    .arguments(Map.of("person_id", gone.toString()))
                    .build());
            assertThat(result.isError())
                    .as(McpTestSupport.textOf(result)).isNotEqualTo(Boolean.TRUE);

            Integer live = jdbc.queryForObject("""
                    select count(*) from membership
                    where org_id = ? and person_id = ? and deleted_at is null
                    """, Integer.class, orgId, gone);
            assertThat(live).isZero();
            then(providerOrgMembership).should().detach(eq(gone), any());
        }
    }
}
