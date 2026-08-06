package ug.co.smsone.mcp.internal;

import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.ApiPermissionEvaluator;

/**
 * The one answer to "may this caller use this tool" — asked twice, by design: {@code tools/list}
 * filters discovery with it, {@code tools/call} re-checks with it (a hidden tool is not an
 * authorization boundary; the re-check is). Delegates to {@link ApiPermissionEvaluator}, so a tool
 * and its REST twin can never disagree: same machine branch, same strict org equality, same
 * fail-closed default.
 */
@Component
class McpAccessPolicy {

    private final ApiPermissionEvaluator permissions;

    McpAccessPolicy(ApiPermissionEvaluator permissions) {
        this.permissions = permissions;
    }

    boolean mayCall(ToolContext context, ToolDefinition tool) {
        if (context == null || context.authentication() == null) {
            return false;
        }
        if (tool.requiredPermission() == null) {
            return true; // any authenticated caller (whoami)
        }
        return context.orgId() != null && permissions.hasPermission(
                context.authentication(), context.orgId(), "organization", tool.requiredPermission());
    }
}
