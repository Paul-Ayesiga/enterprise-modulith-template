package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.NoTenantAxis;

/**
 * The second entry point ADR 0010 §3.2 names, and the ONLY place in this codebase where the clearing
 * can be proved to matter.
 *
 * <p>{@code spring.threads.virtual.enabled} is on, so an HTTP request runs on a thread that is never
 * reused: delete {@code CurrentUserFilter}'s {@code finally} and every test over MockMvc still passes,
 * because there is no next request on that thread to hand the leak to. The leak lives on POOLED
 * PLATFORM threads — the scheduler, {@code @Async} executors, and the MCP SDK's, which owns scheduling
 * for tool handlers and is exactly why {@link McpToolDispatcher} installs the caller's context itself.
 * So the dispatcher is driven here on a real single-thread platform pool, and the assertion that
 * matters is made by a SECOND task on that same thread after the first one finished.
 * {@code Executors.newSingleThreadExecutor} is deliberate: the Spring property does not reach it, so
 * the thread really is pooled and really is reused.
 *
 * <p>Tools are built inline rather than registered through a {@code ToolManifest} — {@code dispatch}
 * takes the definition as an argument, and a test-only tool in the real catalog is a tool in the real
 * catalog. {@code requiredPermission} is null (the {@code whoami} class), so {@link McpAccessPolicy}
 * admits any authenticated caller and the test says nothing about authorization, which has its own.
 *
 * <p><strong>No harness axis, deliberately.</strong> Two of the assertions below are about a thread
 * being left clean, and one — {@code aPlatformTierCallerPinsPlatformRatherThanLeavingTheThread
 * Unpinned} — asserts the dispatcher pins PLATFORM. A harness that pinned PLATFORM first would satisfy
 * that last one with the dispatcher's pin deleted. The opt-out costs nothing here because the work all
 * happens on {@link #POOL}, which the harness never touches; it is declared so the last test, which
 * runs the dispatcher on the test thread, starts from the same absent state a real caller would.
 */
@NoTenantAxis("the dispatcher's own pin and its restore-in-finally are the subject; a harness pin "
        + "would stand in for the platform-tier case and mask a deleted pin")
class McpTenantPinTest extends AbstractIntegrationTest {

    /**
     * One thread, reused across submissions, and platform rather than virtual — the two properties the
     * whole class rests on. A per-task thread would make every assertion below vacuously true.
     */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    @Autowired
    private McpToolDispatcher dispatcher;

    @AfterAll
    static void stopPool() {
        POOL.shutdownNow();
    }

    @AfterEach
    void clearThread() {
        TenantContext.clear();
    }

    @Test
    void theCallersTenantIsOnTheHandlersThreadAndGoneAfterwards() throws Exception {
        UUID orgId = UUID.randomUUID();
        AtomicReference<Tenant> insideHandler = new AtomicReference<>();
        ToolDefinition tool = probe("pin_read", (context, arguments) -> {
            insideHandler.set(TenantContext.current());
            return Map.of("ok", true);
        });

        McpSchema.CallToolResult result = onPool(() -> dispatcher.dispatch(tool, transport(orgId), call(tool)));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(insideHandler.get())
                .as("the handler runs on the caller's schema, not on whatever the pool last pointed at")
                .isEqualTo(new Tenant.Org(orgId));
        assertThat(onPool(TenantContext::current))
                .as("and the next task on that same pooled thread inherits nothing")
                .isEqualTo(Tenant.ABSENT);
    }

    /**
     * The case the {@code finally} exists for. A handler that returns normally is cleaned up by a
     * trailing statement too; one that throws is not — and this dispatcher swallows the throw into an
     * error result, so nothing else in the run would ever notice the tenant it left behind.
     */
    @Test
    void aHandlerThatThrowsLeavesNoTenantOnThePooledThread() throws Exception {
        ToolDefinition tool = probe("pin_boom", (context, arguments) -> {
            throw new IllegalStateException("boom");
        });

        McpSchema.CallToolResult result =
                onPool(() -> dispatcher.dispatch(tool, transport(UUID.randomUUID()), call(tool)));

        assertThat(result.isError()).isTrue();
        assertThat(onPool(TenantContext::current)).isEqualTo(Tenant.ABSENT);
    }

    /**
     * A platform-tier key names no organization, and that is not the poison state — it is the platform.
     * Leaving the handler unpinned would point it at the empty {@code no_tenant} schema, so a tool that
     * reads a platform table would fail with a relation-does-not-exist 500 rather than answer.
     */
    @Test
    void aPlatformTierCallerPinsPlatformRatherThanLeavingTheThreadUnpinned() throws Exception {
        AtomicReference<Tenant> insideHandler = new AtomicReference<>();
        ToolDefinition tool = probe("pin_platform", (context, arguments) -> {
            insideHandler.set(TenantContext.current());
            return Map.of("ok", true);
        });

        onPool(() -> dispatcher.dispatch(tool, transport(null), call(tool)));

        assertThat(insideHandler.get()).isEqualTo(Tenant.PLATFORM);
    }

    /**
     * The restore is a restore, not a clear — the trap the obvious implementation walks into. When the
     * SDK happens to run the handler on the servlet thread, the state to put back is the REQUEST's own
     * tenant, pinned by {@code CurrentUserFilter}; clearing it would leave everything after the tool
     * call reading {@code no_tenant} for the rest of that request, and no MCP assertion would notice.
     */
    @Test
    void aCallOnAnAlreadyPinnedThreadGivesThatThreadItsOwnTenantBack() {
        UUID requestTenant = UUID.randomUUID();
        TenantContext.set(new Tenant.Org(requestTenant));
        ToolDefinition tool = probe("pin_nested", (context, arguments) -> Map.of("ok", true));

        dispatcher.dispatch(tool, transport(UUID.randomUUID()), call(tool));

        assertThat(TenantContext.current()).isEqualTo(new Tenant.Org(requestTenant));
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private static <T> T onPool(Callable<T> task) throws Exception {
        return POOL.submit(task).get();
    }

    private static ToolDefinition probe(String name,
            BiFunction<ToolContext, Map<String, Object>, Object> handler) {
        return new ToolDefinition(name, "Tenant pin probe", "Test-only READ tool that observes the pin.",
                1, "test", ToolDefinition.Kind.READ, null, ToolDefinition.noArguments(), handler);
    }

    private static McpSchema.CallToolRequest call(ToolDefinition tool) {
        return McpSchema.CallToolRequest.builder(tool.name()).build();
    }

    /**
     * The frozen caller the servlet thread hands over. A null {@code orgId} is the platform-tier key;
     * the client IP is judged against the org's allowlist, and an organization with no
     * {@code org_security_policy} row is open by the port's own contract, so no stub is needed.
     */
    private static McpTransportContext transport(UUID orgId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("mcp-tenant-pin")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        Authentication authentication = new JwtAuthenticationToken(jwt, List.of());
        ToolContext context = new ToolContext(authentication, orgId, null,
                "key:" + UUID.randomUUID(), "req-" + UUID.randomUUID(), "127.0.0.1");
        return McpTransportContext.create(Map.of(ToolContext.KEY, context));
    }
}
