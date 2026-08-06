package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ug.co.smsone.access.OrgSecurityPolicies;
import ug.co.smsone.maintenance.MaintenanceState;
import ug.co.smsone.subscription.SubscriptionGate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The dispatcher's guardrails, decided at the module edge: the write guard refuses mutations under
 * a RESTRICT maintenance window or a PAUSED subscription (reads pass), the org IP-allowlist applies
 * to every call, and a denial happens BEFORE the handler — proving the side effect never ran is the
 * point, not just the error shape. Ports are stubbed here (they are the module boundary; their real
 * impls are one repository read each); the full-loop protocol path stays real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpGuardrailsTest extends AbstractIntegrationTest {

    static final AtomicInteger WRITE_TOOL_RUNS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MaintenanceState maintenance;

    @MockitoBean
    private SubscriptionGate subscriptions;

    @MockitoBean
    private OrgSecurityPolicies orgPolicies;

    @TestConfiguration
    static class CountingWriteToolConfig {

        @Bean
        ToolManifest countingWriteManifest() {
            return () -> List.of(new ToolDefinition("orgtest_write", "Counting write tool",
                    "Test-only WRITE tool that counts its executions.", 1, "test",
                    ToolDefinition.Kind.WRITE, "org:update",
                    ToolDefinition.noArguments(),
                    (context, arguments) -> Map.of("runs", WRITE_TOOL_RUNS.incrementAndGet())));
        }
    }

    @BeforeEach
    void openByDefault() {
        WRITE_TOOL_RUNS.set(0);
        given(orgPolicies.ipAllowed(any(), any())).willReturn(true);
        given(maintenance.writeBlockedUntil(any())).willReturn(Optional.empty());
        given(subscriptions.writesBlocked(any())).willReturn(false);
    }

    @Test
    void aMaintenanceWindowRefusesWritesButNotReads() {
        given(maintenance.writeBlockedUntil(any())).willReturn(Optional.of(Instant.parse("2026-08-06T04:30:00Z")));

        try (McpSyncClient client = clientWith("org:read", "org:update")) {
            client.initialize();
            McpSchema.CallToolResult write = client.callTool(
                    McpSchema.CallToolRequest.builder("orgtest_write").build());
            assertThat(write.isError()).isTrue();
            assertThat(McpTestSupport.textOf(write)).contains("SERVICE_UNAVAILABLE").contains("maintenance");
            assertThat(WRITE_TOOL_RUNS).hasValue(0); // refused BEFORE the handler, not after

            McpSchema.CallToolResult read = client.callTool(McpSchema.CallToolRequest.builder("whoami").build());
            assertThat(read.isError()).isNotEqualTo(Boolean.TRUE);
        }
    }

    @Test
    void aPausedSubscriptionMakesTheOrgReadOnly() {
        given(subscriptions.writesBlocked(any())).willReturn(true);

        try (McpSyncClient client = clientWith("org:read", "org:update")) {
            client.initialize();
            McpSchema.CallToolResult write = client.callTool(
                    McpSchema.CallToolRequest.builder("orgtest_write").build());
            assertThat(write.isError()).isTrue();
            assertThat(McpTestSupport.textOf(write)).contains("SUBSCRIPTION_PAUSED");
            assertThat(WRITE_TOOL_RUNS).hasValue(0);

            McpSchema.CallToolResult read = client.callTool(McpSchema.CallToolRequest.builder("whoami").build());
            assertThat(read.isError()).isNotEqualTo(Boolean.TRUE);
        }
    }

    @Test
    void theOrgIpAllowlistGatesEveryCallNotJustWrites() {
        given(orgPolicies.ipAllowed(any(), any())).willReturn(false);

        try (McpSyncClient client = clientWith("org:read")) {
            client.initialize();
            McpSchema.CallToolResult read = client.callTool(McpSchema.CallToolRequest.builder("whoami").build());
            assertThat(read.isError()).isTrue();
            assertThat(McpTestSupport.textOf(read)).contains("ip-allowlist");
        }
    }

    @Test
    void aMissingPermissionDeniesBeforeAnySideEffect() {
        try (McpSyncClient client = clientWith("org:read")) { // holds org:read, NOT org:update
            client.initialize();
            McpSchema.CallToolResult write = client.callTool(
                    McpSchema.CallToolRequest.builder("orgtest_write").build());
            assertThat(write.isError()).isTrue();
            assertThat(McpTestSupport.textOf(write)).contains("FORBIDDEN").contains("org:update");
            assertThat(WRITE_TOOL_RUNS).hasValue(0);
        }
    }

    private McpSyncClient clientWith(String... permissions) {
        McpTestSupport.SeededKey key =
                McpTestSupport.seedOrgKey(jdbc, UUID.randomUUID(), "guardrails", permissions);
        return McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()));
    }
}
