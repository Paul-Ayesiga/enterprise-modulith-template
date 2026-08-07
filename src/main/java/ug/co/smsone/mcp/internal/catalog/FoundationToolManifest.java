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
                "Identify the authenticated caller: the person behind the call (null for an API key — "
                        + "a key acts for nobody), credential kind, and the organization every tool "
                        + "call is scoped to. 'permissions' lists exact codes ONLY for an API key, "
                        + "whose subset is frozen at mint; for a signed-in person it is null on "
                        + "purpose, because membership permissions are resolved per call and no list "
                        + "here could stay true — use the permission-filtered tools/list as the "
                        + "capability answer instead. Call this first to plan with real capabilities, "
                        + "and read personId to know whether the person-only tools are open to you.",
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
        // personId, not a subject: the platform's own identifier for the caller, null for a machine
        // because a robot is not a person. An agent that needs to know "who am I durably" reads this
        // for a human and keyId for a key — the two id spaces are unrelated and are not merged here.
        payload.put("personId", context.personId() == null ? null : context.personId().toString());
        payload.put("orgId", context.orgId() == null ? null : context.orgId().toString());
        if (context.authentication() instanceof ApiKeyAuthenticationToken token) {
            ApiKeyPrincipal key = token.getPrincipal();
            payload.put("authKind", "api_key");
            payload.put("keyId", key.keyId().toString());
            payload.put("keyName", key.name());
            payload.put("permissions", key.permissions().stream().sorted().toList());
            payload.put("platformTier", key.platformTier());
        } else if (context.authentication()
                instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) {
            payload.put("authKind", "oauth"); // a consented connector token (Phase 7)
            payload.put("permissions", null); // membership permissions resolve per call, not per token
        } else {
            payload.put("authKind", "user");
            payload.put("permissions", null); // human permissions resolve per call, not per token
        }
        return payload;
    }
}
