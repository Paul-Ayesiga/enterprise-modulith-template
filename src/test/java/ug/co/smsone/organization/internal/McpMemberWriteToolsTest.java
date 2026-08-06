package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.mcp.internal.McpTestSupport;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The member WRITE tools through the real MCP loop, from the org package so the Keycloak gateway
 * can be stubbed (the same edge {@code OrgRbacApiTest} mocks). What this pins: an API key's minted
 * permission subset is its held set for the escalation guard — it can hand on roles whose
 * permissions sit inside that subset, nothing more, and a denial happens BEFORE any provisioning
 * side effect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpMemberWriteToolsTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private UserProvisioning userProvisioning;

    @MockitoBean
    private KeycloakOrgAdminGateway keycloakOrg;

    @Test
    void aKeyWithInvitePermissionInvitesWithinItsMintedSubset() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.seedOrg(jdbc, orgId, "inv-" + orgId.toString().substring(0, 8), "Invites");
        McpTestSupport.seedRole(jdbc, orgId, "MEMBER", "org:read");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "inviter",
                "member:invite", "org:read");
        given(userProvisioning.provision(any()))
                .willReturn(new ProvisionedUser("subject-new", "new@acme.test", false));

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("member_invite")
                    .arguments(Map.of("email", "new@acme.test", "role_code", "MEMBER"))
                    .build());
            assertThat(result.isError())
                    .as(McpTestSupport.textOf(result)).isNotEqualTo(Boolean.TRUE);

            then(keycloakOrg).should().addMember(orgId, "subject-new");
            Integer memberships = jdbc.queryForObject(
                    "select count(*) from membership where org_id = ? and user_subject = 'subject-new'",
                    Integer.class, orgId);
            assertThat(memberships).isEqualTo(1);
        }
    }

    @Test
    void aKeyCannotGrantARoleBeyondItsMintedSubset() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.seedOrg(jdbc, orgId, "esc-" + orgId.toString().substring(0, 8), "Escalation");
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
            then(userProvisioning).should(never()).provision(any());
            then(keycloakOrg).should(never()).addMember(any(), any());
        }
    }

    @Test
    void aKeyWithoutThePermissionNeverReachesProvisioning() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.seedOrg(jdbc, orgId, "den-" + orgId.toString().substring(0, 8), "Denied");
        McpTestSupport.seedRole(jdbc, orgId, "MEMBER", "org:read");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "reader", "org:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("member_invite")
                    .arguments(Map.of("email", "no@acme.test", "role_code", "MEMBER"))
                    .build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("member:invite");
            then(userProvisioning).should(never()).provision(any());
        }
    }

    @Test
    void memberRemoveEndsTheMembershipAndUnlinksKeycloak() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.seedOrg(jdbc, orgId, "rem-" + orgId.toString().substring(0, 8), "Removals");
        // An OWNER member must remain — remove() protects the last owner; the target is a MEMBER.
        UUID ownerRole = McpTestSupport.seedRole(jdbc, orgId, "OWNER", "org:read", "member:remove");
        UUID memberRole = McpTestSupport.seedRole(jdbc, orgId, "MEMBER", "org:read");
        McpTestSupport.seedMembership(jdbc, orgId, "subject-owner", ownerRole);
        McpTestSupport.seedMembership(jdbc, orgId, "subject-gone", memberRole);
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "remover",
                "member:remove", "org:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("member_remove")
                    .arguments(Map.of("subject", "subject-gone"))
                    .build());
            assertThat(result.isError())
                    .as(McpTestSupport.textOf(result)).isNotEqualTo(Boolean.TRUE);

            Integer live = jdbc.queryForObject("""
                    select count(*) from membership
                    where org_id = ? and user_subject = 'subject-gone' and deleted_at is null
                    """, Integer.class, orgId);
            assertThat(live).isZero();
            then(keycloakOrg).should().removeMember(orgId, "subject-gone");
        }
    }
}
