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
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Puts the caller's active org on the MDC (key {@code org_id}) for the length of the request — the
 * request-id filter's pattern applied to WHO the request serves, so every log line a tenant request
 * produces carries the tenant without any layer passing it along. Runs after the security chain
 * (the claim must be resolved) and does nothing for platform/unscoped calls.
 *
 * <p>The value is {@code organization.id}, our own tenant key — the same id the URLs, the audit trail
 * and the usage records use. It used to be the Keycloak organization id straight off the claim, so a log
 * line and a database row named the same tenant two different ways; the edge resolves the claim now
 * ({@link OrgLookup}) and this filter just reads the answer.
 *
 * <p><b>It shares {@code @Order(-1)} with {@link CurrentUserFilter}, and since ADR 0010 that tie is no
 * longer harmless on its own.</b> Whichever of the two runs first performs the single resolution and
 * the other is served the memo — that part still holds. But the resolution is a database read, and
 * only {@code CurrentUserFilter} pins an axis before performing it. Win the tie and this filter would
 * do it with none, on the empty {@code no_tenant} schema, failing every authenticated request. The
 * tie is broken today by bean-discovery order, which is filesystem order — not something to stake the
 * edge on. Declaring the axis here restores the property the ordering comment claims: it genuinely
 * does not matter which of the two runs first.
 */
@Component
@Order(-1) // after impersonation (-2) so the EFFECTIVE org lands; before rate limit (0) so 429s carry it
public class OrgMdcFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "org_id";

    private final CurrentUserProvider currentUser;

    public OrgMdcFilter(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        // Declares the platform axis for the caller resolution; ADR 0010 §3.4. See the class note for
        // why the shared @Order(-1) makes this necessary rather than belt-and-braces. When
        // CurrentUserFilter won the tie this is a pure ThreadLocal set/restore — the memo answers and
        // no connection is borrowed — and callAsPlatform restores, so the tenant it pinned survives.
        UUID orgId = TenantContext.callAsPlatform(
                () -> currentUser.currentUser().map(CurrentUser::organizationId).orElse(null));
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
