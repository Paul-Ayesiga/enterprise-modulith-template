package ug.co.smsone.mcp.internal;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.ForwardedClientIp;
import ug.co.smsone.shared.web.RequestIdFilter;

/**
 * Runs on the servlet thread — the ONLY place the security context, MDC and request are all
 * guaranteed — and freezes what a tool call needs into the transport context as a
 * {@link ToolContext}. Handlers must read that, never a thread-local: the SDK owns the execution
 * thread after this point.
 */
@Component
class McpRequestContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

    private final CurrentUserProvider currentUser;
    private final ForwardedClientIp forwardedClientIp;

    McpRequestContextExtractor(CurrentUserProvider currentUser, ForwardedClientIp forwardedClientIp) {
        this.currentUser = currentUser;
        this.forwardedClientIp = forwardedClientIp;
    }

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        CurrentUser caller = currentUser.currentUser().orElse(null);
        UUID orgId = caller == null ? null : caller.organizationId();
        UUID personId = caller == null ? null : caller.personId();
        ToolContext context = new ToolContext(authentication, orgId, personId,
                currentUser.currentPrincipalKey().orElse(null),
                MDC.get(RequestIdFilter.MDC_KEY), forwardedClientIp.clientIp(request));
        return McpTransportContext.create(Map.of(ToolContext.KEY, context));
    }

    static ToolContext toolContext(McpTransportContext transportContext) {
        return transportContext.get(ToolContext.KEY) instanceof ToolContext context ? context : null;
    }
}
