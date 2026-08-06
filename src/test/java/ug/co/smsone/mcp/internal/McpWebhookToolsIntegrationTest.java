package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 2: the full webhook management surface over the real loop — create (secret shown once,
 * URL-guarded), read (secret masked), update-wholesale, rotate, the delivery log, redelivery of a
 * dead-lettered row (fenced: only FAILED), and delete-with-surviving-log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpWebhookToolsIntegrationTest extends AbstractIntegrationTest {

    private static final String EVENT = "org.member.added";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createShowsTheSecretOnceThenEveryReadMasksIt() {
        try (McpSyncClient client = client()) {
            client.initialize();
            Map<String, Object> created = call(client, "webhook_create",
                    Map.of("url", "https://receiver.example.org/hooks", "events", List.of(EVENT)));
            assertThat((String) created.get("plainSecret")).startsWith("whsec_").hasSizeGreaterThan(20);
            String id = subscriptionId(created);

            Map<String, Object> read = call(client, "webhook_get", Map.of("subscription_id", id));
            assertThat(read).containsEntry("maskedSecret", "whsec_••••••");
            assertThat(read).doesNotContainKey("plainSecret");

            Map<String, Object> listed = call(client, "webhooks_list", Map.of());
            assertThat((List<?>) listed.get("items")).isNotEmpty();
        }
    }

    @Test
    void aNonHttpUrlIsRefusedByTheOutboundGuard() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("webhook_create")
                    .arguments(Map.of("url", "ftp://receiver.example.org/x", "events", List.of(EVENT)))
                    .build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("VALIDATION_FAILED");
        }
    }

    @Test
    void anUnknownEventCodeIsA422NotACreation() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("webhook_create")
                    .arguments(Map.of("url", "https://receiver.example.org/hooks",
                            "events", List.of("org.not.a.thing")))
                    .build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("Unknown event type");
        }
    }

    @Test
    void updateReplacesWholesaleAndRotateMintsANewSecret() {
        try (McpSyncClient client = client()) {
            client.initialize();
            Map<String, Object> created = call(client, "webhook_create",
                    Map.of("url", "https://receiver.example.org/v1", "events", List.of(EVENT)));
            String id = subscriptionId(created);
            String firstSecret = (String) created.get("plainSecret");

            Map<String, Object> updated = call(client, "webhook_update", Map.of(
                    "subscription_id", id, "url", "https://receiver.example.org/v2",
                    "events", List.of("org.member.removed"), "status", "DISABLED"));
            assertThat(updated).containsEntry("url", "https://receiver.example.org/v2")
                    .containsEntry("status", "DISABLED");
            assertThat((List<?>) updated.get("events")).isEqualTo(List.of("org.member.removed"));

            Map<String, Object> rotated = call(client, "webhook_rotate_secret",
                    Map.of("subscription_id", id));
            assertThat((String) rotated.get("plainSecret")).startsWith("whsec_")
                    .isNotEqualTo(firstSecret);
        }
    }

    @Test
    void aFailedDeliveryRedeliversOnceAndOnlyOnce() {
        try (McpSyncClient client = client()) {
            client.initialize();
            Map<String, Object> created = call(client, "webhook_create",
                    Map.of("url", "https://receiver.example.org/dead", "events", List.of(EVENT)));
            String id = subscriptionId(created);
            UUID deliveryId = seedFailedDelivery(UUID.fromString(id));

            Map<String, Object> log = call(client, "webhook_deliveries", Map.of("subscription_id", id));
            assertThat(items(log)).anySatisfy(row -> assertThat(row).containsEntry("status", "FAILED"));

            Map<String, Object> requeued = call(client, "webhook_redeliver",
                    Map.of("subscription_id", id, "delivery_id", deliveryId.toString()));
            assertThat(requeued).containsEntry("requeued", deliveryId.toString());
            assertThat(jdbc.queryForObject(
                    "select status from webhook_delivery where id = ?", String.class, deliveryId))
                    .isEqualTo("PENDING");

            // The fence: a second redeliver finds the row no longer FAILED and reports the conflict.
            McpSchema.CallToolResult second = client.callTool(McpSchema.CallToolRequest.builder("webhook_redeliver")
                    .arguments(Map.of("subscription_id", id, "delivery_id", deliveryId.toString()))
                    .build());
            assertThat(second.isError()).isTrue();
            assertThat(McpTestSupport.textOf(second)).contains("CONFLICT");
        }
    }

    @Test
    void deleteStopsTheSubscriptionButTheLogStaysReadable() {
        try (McpSyncClient client = client()) {
            client.initialize();
            Map<String, Object> created = call(client, "webhook_create",
                    Map.of("url", "https://receiver.example.org/gone", "events", List.of(EVENT)));
            String id = subscriptionId(created);
            seedFailedDelivery(UUID.fromString(id));

            call(client, "webhook_delete", Map.of("subscription_id", id));

            McpSchema.CallToolResult get = client.callTool(McpSchema.CallToolRequest.builder("webhook_get")
                    .arguments(Map.of("subscription_id", id)).build());
            assertThat(get.isError()).isTrue();
            assertThat(McpTestSupport.textOf(get)).contains("RESOURCE_NOT_FOUND");

            Map<String, Object> log = call(client, "webhook_deliveries", Map.of("subscription_id", id));
            assertThat(items(log)).isNotEmpty(); // "what did we send that endpoint?" survives the delete
        }
    }

    private final UUID orgId = UUID.randomUUID();

    private McpSyncClient client() {
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "hooks", "webhook:manage");
        return McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()));
    }

    private UUID seedFailedDelivery(UUID subscriptionId) {
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                insert into webhook_delivery (id, subscription_id, org_id, event_type, payload, status,
                                              attempts, max_attempts, next_attempt_at, last_error, created_at)
                values (?, ?, ?, ?, '{}', 'FAILED', 8, 8, now(), 'receiver answered 500', now())
                """, deliveryId, subscriptionId, orgId, EVENT);
        return deliveryId;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpSyncClient client, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = client.callTool(
                McpSchema.CallToolRequest.builder(tool).arguments(args).build());
        assertThat(result.isError()).as("%s should succeed: %s", tool, McpTestSupport.textOf(result))
                .isNotEqualTo(Boolean.TRUE);
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> windowed) {
        return (List<Map<String, Object>>) windowed.get("items");
    }

    @SuppressWarnings("unchecked")
    private static String subscriptionId(Map<String, Object> created) {
        return (String) ((Map<String, Object>) created.get("subscription")).get("id");
    }
}
