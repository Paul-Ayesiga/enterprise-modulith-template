package ug.co.smsone.mcp.internal;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-side halves of the two real contracts: mint a key exactly as {@code ApiKeyHashing} would
 * (same {@code sk_<prefix>.<secret>} shape, same unsalted SHA-256 of the secret) straight into
 * {@code api_key}, and build a REAL SDK client speaking streamable HTTP at the booted port — the
 * full-loop harness the plan gates every phase on. Public: catalog phases test from the OWNING
 * module's test package when they must stub internal collaborators (Keycloak gateways).
 */
public final class McpTestSupport {

    public record SeededKey(UUID keyId, String presented) {
    }

    private McpTestSupport() {
    }

    public static SeededKey seedOrgKey(JdbcTemplate jdbc, UUID orgId, String name, String... permissions) {
        UUID keyId = UUID.randomUUID();
        String prefix = "sk_" + randomHex(6);
        String secret = randomHex(24);
        // created_by/updated_by are uuid holding person.id and are LEFT NULL: V10's rule is that null
        // means a non-person actor, and a fixture is exactly that. The literal 'test' used to go here.
        jdbc.update("""
                insert into api_key (id, org_id, name, prefix, secret_hash, permissions,
                                     version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 0, now(), now())
                """, keyId, orgId, name, prefix, sha256(secret), String.join(",", permissions));
        return new SeededKey(keyId, prefix + "." + secret);
    }

    static void revoke(JdbcTemplate jdbc, UUID keyId) {
        jdbc.update("update api_key set deleted_at = now(), version = version + 1 where id = ?", keyId);
    }

    // --- Cross-module row seeding (tables, not services: MCP tests sit outside the owning
    // packages, and rows are the honest fixture for read tools) --------------------------------

    /** organization.id IS the tenant key now — the caller supplies it, nothing mints a provider id. */
    public static void seedOrg(JdbcTemplate jdbc, UUID orgId, String alias, String name) {
        jdbc.update("""
                insert into organization (id, alias, name, status, version, created_at)
                values (?, ?, ?, 'ACTIVE', 0, now())
                """, orgId, alias, name);
    }

    public static UUID seedRole(JdbcTemplate jdbc, UUID orgId, String code, String... permissions) {
        UUID roleId = UUID.randomUUID();
        jdbc.update("""
                insert into org_role (id, org_id, code, name, system_role, version, created_at)
                values (?, ?, ?, ?, false, 0, now())
                """, roleId, orgId, code, code);
        for (String permission : permissions) {
            // The @Enumerated(STRING) collection stores enum NAMES; callers pass wire codes.
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)",
                    roleId, ug.co.smsone.organization.Permission.fromCode(permission).name());
        }
        return roleId;
    }

    public static void seedMembership(JdbcTemplate jdbc, UUID orgId, UUID personId, UUID roleId) {
        jdbc.update("""
                insert into membership (id, org_id, person_id, role_id, status, version, created_at)
                values (?, ?, ?, ?, 'ACTIVE', 0, now())
                """, UUID.randomUUID(), orgId, personId, roleId);
    }

    /** The first text block — the human-readable mirror every tool result carries. */
    public static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst()
                .orElse("");
    }

    public static McpSyncClient client(int port, Map<String, String> headers) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        headers.forEach(requestBuilder::header);
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint(McpEndpointConfig.ENDPOINT)
                        .requestBuilder(requestBuilder)
                        .build())
                .requestTimeout(Duration.ofSeconds(20))
                .build();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
