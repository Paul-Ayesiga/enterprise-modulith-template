package ug.co.smsone.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Authenticates {@code X-Api-Key: sk_<prefix>.<secret>} — registered INSIDE the security chain,
 * before the bearer filter (a request presenting a key is a machine; a bearer alongside it is
 * ignored). An invalid or unverifiable key authenticates NOTHING: the request continues
 * anonymous and the authorization layer answers 401 — never a special error that would confirm
 * key-prefix existence to a prober.
 *
 * <p>{@code Authorization: Bearer sk_…} is accepted as a second spelling of the same credential:
 * remote MCP clients (the Claude API connector's {@code authorization_token}, Managed Agents
 * {@code static_bearer} vaults) can only send a bearer header. The {@code sk_} prefix is what makes
 * this safe — a JWT can never start with it, so the JWT filter behind us loses nothing, and a
 * non-key bearer is left untouched for it.
 *
 * <p><b>The earliest database read in the whole request (ADR 0010 §3.3).</b> This filter sits inside
 * the security chain at {@code @Order(-100)}, ninety-nine places ahead of the filter that learns which
 * tenant is calling — and it cannot be otherwise, because {@code api_key} is HOW the tenant is
 * discovered ({@code findByPrefix} probes a global unique index; a platform-tier key names no org at
 * all). That makes the lookup platform-tier by construction, and the axis has to be declared here
 * rather than inferred.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String KEY_PREFIX = "sk_";

    private final ObjectProvider<ApiKeyAuthenticator> authenticator;

    public ApiKeyAuthenticationFilter(ObjectProvider<ApiKeyAuthenticator> authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String presented = presentedKey(request);
        ApiKeyAuthenticator verifier = authenticator.getIfAvailable();
        if (presented != null && verifier != null) {
            // Declares the platform axis for the api_key probe; ADR 0010 §3.4. Nothing has pinned a
            // tenant this early — the key is what names one — so an undeclared borrow resolves ABSENT
            // to the empty no_tenant schema and every machine caller 401s on `relation "api_key" does
            // not exist`. That is the entire API-key and MCP surface, so this one line is load-bearing
            // for every non-human client of the platform.
            //
            // callAsPlatform and not a bare set/clear, for two reasons that both bite:
            //  * ApiKeyAuthenticatorImpl.authenticate is @Transactional, so the pin CANNOT live inside
            //    it — TenantContext.set throws in an active transaction by design, because the schema
            //    is chosen at connection borrow and the transaction has already bound one. Outside the
            //    call is the only correct place.
            //  * it restores rather than clears, which keeps the same authenticator safe when it is
            //    reached from a thread that already has an axis — GatewayIntrospectionController calls
            //    it from inside a request that CurrentUserFilter has already pinned.
            //
            // Scoped to the lookup ALONE, never around chain.doFilter: a platform pin held across the
            // chain would outlive the point where CurrentUserFilter sets the caller's real tenant, and
            // "we never learned who this is, so read platform" is the fail-open §3.3 exists to forbid.
            TenantContext.callAsPlatform(() -> verifier.authenticate(presented))
                    .ifPresent(principal -> SecurityContextHolder.getContext()
                            .setAuthentication(new ApiKeyAuthenticationToken(principal)));
        }
        chain.doFilter(request, response);
    }

    /** The key from {@code X-Api-Key}, else from a {@code Bearer sk_…} Authorization header. */
    private static String presentedKey(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (token.startsWith(KEY_PREFIX)) {
                return token;
            }
        }
        return null;
    }
}
