package ug.co.smsone.mcp.internal.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ug.co.smsone.mcp.internal.ToolContext;
import ug.co.smsone.mcp.internal.ToolDefinition;
import ug.co.smsone.mcp.internal.ToolManifest;
import ug.co.smsone.shared.security.ApiKeyAuthenticationToken;
import ug.co.smsone.shared.security.ApiKeyPrincipal;

/**
 * The tools every authenticated caller gets regardless of permissions — today {@code whoami}, the
 * agent's bootstrap: who am I, which org am I scoped to, what may I do. An agent that calls this
 * first plans with its real capabilities instead of discovering them by 403.
 */
@Component
class FoundationToolManifest implements ToolManifest {

    @Override
    public List<ToolDefinition> tools() {
        return List.of(new ToolDefinition(
                "whoami",
                "Who am I",
                "Identify the authenticated caller: durable subject, credential kind, the organization "
                        + "every tool call is scoped to, and the exact permission codes this credential "
                        + "holds. Call this first to plan with real capabilities.",
                1,
                "foundation",
                ToolDefinition.Kind.READ,
                null,
                ToolDefinition.noArguments(),
                FoundationToolManifest::whoami));
    }

    private static Object whoami(ToolContext context, Map<String, Object> arguments) {
        // LinkedHashMap over Map.of: null-tolerant (orgId is null for platform keys) and the field
        // order below is the order agents read.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subject", context.subject());
        payload.put("orgId", context.orgId() == null ? null : context.orgId().toString());
        if (context.authentication() instanceof ApiKeyAuthenticationToken token) {
            ApiKeyPrincipal key = token.getPrincipal();
            payload.put("authKind", "api_key");
            payload.put("keyName", key.name());
            payload.put("permissions", key.permissions().stream().sorted().toList());
            payload.put("platformTier", key.platformTier());
        } else {
            payload.put("authKind", "user");
            payload.put("permissions", null); // human permissions resolve per call, not per token
        }
        return payload;
    }
}
