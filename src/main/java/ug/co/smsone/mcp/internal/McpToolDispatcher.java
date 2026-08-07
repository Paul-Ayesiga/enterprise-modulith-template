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

    McpSchema.CallToolResult dispatch(ToolDefinition tool, McpTransportContext transportContext,
            McpSchema.CallToolRequest request) {
        ToolContext context = McpRequestContextExtractor.toolContext(transportContext);
        if (context == null || context.authentication() == null) {
            // The security chain 401s unauthenticated requests before the servlet; reaching here
            // without a principal means a wiring regression — refuse, never limp.
            return outcome(tool, context, "denied",
                    error(ErrorCode.UNAUTHORIZED, "Authentication required.", context));
        }
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
     * of a port — audit attribution, {@code created_by}, log lines — reads the thread's security
     * context and MDC. Install the caller's context for the duration and restore in a finally: a
     * pooled thread must never inherit someone else's identity (the {@code ImpersonationFilter}
     * rule, applied to the one other place this codebase swaps a context).
     *
     * <p>The mutation audit is written INSIDE that window, not after the call returns, for exactly the
     * same reason: {@link #auditMutation}'s person branch resolves the actor from this thread, and one
     * line later the finally below has already handed the thread back to whoever owned it. Auditing
     * outside the window is only harmless while the SDK happens to run handlers on the servlet thread —
     * the moment it doesn't, a human's mutation is recorded as nobody's.
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
