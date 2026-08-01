package ug.co.smsone.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the token's active org on the MDC (key {@code org_id}) for the length of the request — the
 * request-id filter's pattern applied to WHO the request serves, so every log line a tenant request
 * produces carries the tenant without any layer passing it along. Runs after the security chain
 * (the claim must be resolved) and does nothing for platform/unscoped calls.
 */
@Component
@Order(0)
public class OrgMdcFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "org_id";

    private final CurrentUserProvider currentUser;

    public OrgMdcFilter(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        UUID orgId = currentUser.currentUser().map(CurrentUser::activeOrgId).orElse(null);
        if (orgId == null) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(MDC_KEY, orgId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
