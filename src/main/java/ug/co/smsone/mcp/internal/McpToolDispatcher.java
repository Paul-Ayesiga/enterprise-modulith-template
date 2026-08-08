package ug.co.smsone.mcp.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ApiException;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.RequestIdFilter;
import ug.co.smsone.access.OrgSecurityPolicies;

/**
 * The single policy point for every tool call — the pipeline the plan fixes: permission check, org
 * IP-allowlist, write guard (mutations), then the handler; audit (mutations), metrics and the
 * requestId on the way out. Handlers never see an unauthorized call, and nothing here does I/O
 * beyond one policy read plus what the handler's port does.
 *
 * <p>Error discipline mirrors REST: a typed {@link ApiException} becomes a tool error carrying the
 * wire {@code ErrorCode} name, its curated detail and the requestId; anything unexpected logs the
 * stack with the requestId and answers a single generic sentence — never an exception message.
 */
@Component
class McpToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpToolDispatcher.class);

    private final McpToolRegistry registry;
    private final McpAccessPolicy accessPolicy;
    private final McpWriteGuard writeGuard;
    private final OrgSecurityPolicies orgPolicies;
    private final AuditLog auditLog;
    private final MeterRegistry meters;
    private final ObjectMapper json;

    McpToolDispatcher(McpToolRegistry registry, McpAccessPolicy accessPolicy, McpWriteGuard writeGuard,
            OrgSecurityPolicies orgPolicies, AuditLog auditLog, MeterRegistry meters, ObjectMapper json) {
        this.registry = registry;
        this.accessPolicy = accessPolicy;
        this.writeGuard = writeGuard;
        this.orgPolicies = orgPolicies;
        this.auditLog = auditLog;
        this.meters = meters;
        this.json = json;
    }

    /** The SDK-facing catalog: every registered tool, each routed through {@link #dispatch}. */
    List<McpStatelessServerFeatures.SyncToolSpecification> toolSpecifications() {
        return registry.all().stream()
                .map(tool -> McpStatelessServerFeatures.SyncToolSpecification.builder()
                        .tool(McpToolRegistry.toMcpTool(tool))
                        .callHandler((transportContext, request) -> dispatch(tool, transportContext, request))
                        .build())
                .toList();
    }

    /**
     * <b>The caller's axis is declared here, around the WHOLE dispatch, not around the handler.</b>
     * (ADR 0010 §3.2, entry point 2.) The obvious reading of that section is that the pin belongs beside
     * the {@code SecurityContextHolder.setContext} in {@link #callOnCallerContext} — and it is wrong,
     * because three of the checks above the handler read the caller's own tenant-tier tables:
     * {@link McpAccessPolicy} resolves permissions through {@code membership}/{@code org_role}/
     * {@code role_permission}, {@link OrgSecurityPolicies#ipAllowed} reads {@code org_security_policy},
     * and {@link McpWriteGuard} reads {@code org_subscription}. On the servlet thread that happens to
     * work — {@code CurrentUserFilter} pinned the request's tenant two filters up — but the SDK owns
     * scheduling and may hand this method a POOLED thread that nobody pinned, where absent routes to the
     * empty {@code no_tenant} schema and the IP allowlist read dies with {@code relation
     * "org_security_policy" does not exist}. The pin has to bracket everything that touches the
     * database, and the frozen {@link ToolContext} is the only thing on this thread that knows the
     * tenant.
     *
     * <p>Before the axis, deliberately, sits exactly one thing: the missing-principal refusal, which
     * reads nothing and has no tenant to name.
     *
     * <p><b>Restore, not clear.</b> When the SDK does run on the servlet thread the previous state is the
     * request's own tenant, and clearing would leave everything after the tool call reading
     * {@code no_tenant} for the rest of that request — with no MCP assertion anywhere to notice. The
     * restore is safe in a {@code finally} because any transaction a handler opened has already unwound
     * by the time this frame runs; {@code TenantContext.set} throws inside an active one, and a throw
     * from here would swallow the handler's own failure.
     */
    McpSchema.CallToolResult dispatch(ToolDefinition tool, McpTransportContext transportContext,
            McpSchema.CallToolRequest request) {
        ToolContext context = McpRequestContextExtractor.toolContext(transportContext);
        if (context == null || context.authentication() == null) {
            // The security chain 401s unauthenticated requests before the servlet; reaching here
            // without a principal means a wiring regression — refuse, never limp.
            return outcome(tool, context, "denied",
                    error(ErrorCode.UNAUTHORIZED, "Authentication required.", context));
        }
        Tenant previousTenant = TenantContext.current();
        try {
            TenantContext.set(axisOf(context));
            return authorizeAndCall(tool, context, request);
        } finally {
            TenantContext.restore(previousTenant);
        }
    }

    /**
     * A null org is a PLATFORM-tier key, not a missing tenant: {@link McpAccessPolicy} denies every
     * org-scoped tool to it, so what such a caller can reach is the permission-free catalog, and that
     * reads platform-tier facts. Leaving it absent instead would point the whole dispatch at the empty
     * schema and turn a legitimate {@code whoami} into a 500.
     */
    private static Tenant axisOf(ToolContext context) {
        return context.orgId() == null ? Tenant.PLATFORM : Tenant.of(context.orgId());
    }

    /** The pipeline proper, running on the caller's axis — see {@link #dispatch}. */
    private McpSchema.CallToolResult authorizeAndCall(ToolDefinition tool, ToolContext context,
            McpSchema.CallToolRequest request) {
        if (!accessPolicy.mayCall(context, tool)) {
            return outcome(tool, context, "denied", error(ErrorCode.FORBIDDEN,
                    "This tool requires the '" + tool.requiredPermission() + "' permission.", context));
        }
        if (context.orgId() != null && !orgPolicies.ipAllowed(context.orgId(), context.clientIp())) {
            return outcome(tool, context, "denied", error(ErrorCode.FORBIDDEN,
                    "Blocked by your organization's security policy (ip-allowlist).", context));
        }
        if (tool.kind().mutates()) {
            Optional<McpWriteGuard.Refusal> refusal = writeGuard.check(context.orgId());
            if (refusal.isPresent()) {
                return outcome(tool, context, refusal.get().outcome(),
                        error(refusal.get().code(), refusal.get().detail(), context));
            }
        }
        Timer.Sample sample = Timer.start(meters);
        try {
            Object payload = callOnCallerContext(tool, context, request);
            return outcome(tool, context, "ok", result(payload, context));
        } catch (ApiException ex) {
            return outcome(tool, context, "error", error(ex.errorCode(), ex.detail(), context));
        } catch (RuntimeException ex) {
            log.error("MCP tool {} failed (requestId={}): {}", tool.name(), context.requestId(), ex.toString(), ex);
            return outcome(tool, context, "error", error(ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred. Quote the requestId to support.", context));
        } finally {
            sample.stop(Timer.builder("smsone.mcp.tool.duration")
                    .description("MCP tool call latency")
                    .tag("tool", tool.name())
                    .register(meters));
        }
    }

    /**
     * Handlers may run off the servlet thread (the SDK owns scheduling), but everything downstream
     * of a port — audit attribution, {@code created_by}, log lines — reads the thread's security context
     * and MDC. Install the caller's facts for the duration and restore them in a finally: a pooled
     * thread must never inherit someone else's identity (the {@code ImpersonationFilter} rule, applied
     * to the one other place this codebase swaps a context).
     *
     * <p>The tenant is the third such fact and is NOT installed here — {@link #dispatch} declares it one
     * frame up, because the checks between there and here read the caller's tenant-tier tables too. Read
     * that javadoc: putting the pin in this method is the plausible-looking version of this class that
     * 500s on a pooled thread.
     *
     * <p>The mutation audit is written INSIDE this window, not after the call returns, for exactly the
     * same reason the context is installed at all: {@link #auditMutation}'s person branch resolves the
     * actor from this thread, and one line later the finally below has already handed the thread back to
     * whoever owned it. Auditing outside the window is only harmless while the SDK happens to run
     * handlers on the servlet thread — the moment it doesn't, a human's mutation is recorded as nobody's.
     */
    private Object callOnCallerContext(ToolDefinition tool, ToolContext context,
            McpSchema.CallToolRequest request) {
        SecurityContext previous = SecurityContextHolder.getContext();
        String previousRequestId = MDC.get(RequestIdFilter.MDC_KEY);
        try {
            SecurityContextHolder.setContext(new SecurityContextImpl(context.authentication()));
            if (context.requestId() != null) {
                MDC.put(RequestIdFilter.MDC_KEY, context.requestId());
            }
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            Object payload = tool.handler().apply(context, arguments);
            if (tool.kind().mutates()) {
                auditMutation(tool, context);
            }
            return payload;
        } finally {
            SecurityContextHolder.setContext(previous);
            if (previousRequestId != null) {
                MDC.put(RequestIdFilter.MDC_KEY, previousRequestId);
            } else {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        }
    }

    /**
     * Attribute the mutation. Both branches write the same row for the same action; what differs is
     * where the actor comes from, and the rule is that the actor is a PARAMETER only where no person
     * acted at all — see {@link AuditLog#recordExternal}.
     *
     * <p>A person-backed caller (an OAuth connector) keeps {@link AuditLog#record}: the security
     * context is the authority on who acted, and it knows what no argument here could — that a write
     * made inside an impersonation session is answerable to the OPERATOR, not to the person the request
     * runs as. Handing {@code personId()} over as a parameter would silently overwrite that with the
     * wrong human, in the one table whose whole job is to say who acted.
     *
     * <p>A machine key has NO person, so {@code record()} resolves it to {@code Attribution.NOBODY} and
     * the row states only that SOMETHING mutated the tenant — every key in an org indistinguishable
     * from every other, which is not an audit trail. Nothing is on the thread here for a parameter to
     * misstate, so {@code recordExternal} takes the principal key: {@code actor_person_id} stays NULL
     * (still the truthful answer — no human is answerable for a robot) while
     * {@code edgePrincipal=key:<uuid>} rides {@code to_state} and names the credential.
     *
     * <p>The trap: {@code principalKey()} is documented as a diagnostic label that is never stored, and
     * this respects that. It lands in free text beside the outcome it describes, never in the typed
     * column that means "who" and never compared to a {@code person.id} — the same non-identity use the
     * gateway edge-audit path already makes of the very same shape.
     */
    private void auditMutation(ToolDefinition tool, ToolContext context) {
        if (context.personId() != null) {
            auditLog.record("mcp.tool_invoked", context.orgId(), tool.name());
        } else {
            auditLog.recordExternal("mcp.tool_invoked", context.orgId(), context.principalKey(),
                    tool.name(), null, null);
        }
    }

    private McpSchema.CallToolResult result(Object payload, ToolContext context) {
        return McpSchema.CallToolResult.builder()
                .structuredContent(payload)
                .addTextContent(toJson(payload))
                .meta(Map.of("smsone/requestId", requestIdOf(context)))
                .build();
    }

    private McpSchema.CallToolResult error(ErrorCode code, String detail, ToolContext context) {
        Map<String, Object> payload = Map.of("error", Map.of(
                "code", code.name(), "detail", detail, "requestId", requestIdOf(context)));
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(payload)
                .addTextContent(toJson(payload))
                .meta(Map.of("smsone/requestId", requestIdOf(context)))
                .build();
    }

    private McpSchema.CallToolResult outcome(ToolDefinition tool, ToolContext context, String outcome,
            McpSchema.CallToolResult result) {
        Counter.builder("smsone.mcp.tool.calls")
                .description("MCP tool calls by tool, safety kind and outcome")
                .tag("tool", tool.name())
                .tag("kind", tool.kind().name())
                .tag("outcome", outcome)
                .register(meters)
                .increment();
        if (!"ok".equals(outcome)) {
            log.info("MCP tool {} refused ({}) for {} (requestId={})",
                    tool.name(), outcome, context == null ? "?" : context.principalKey(),
                    context == null ? "?" : context.requestId());
        }
        return result;
    }

    private String toJson(Object payload) {
        return json.writeValueAsString(payload);
    }

    private static String requestIdOf(ToolContext context) {
        return context == null || context.requestId() == null ? "" : context.requestId();
    }
}
