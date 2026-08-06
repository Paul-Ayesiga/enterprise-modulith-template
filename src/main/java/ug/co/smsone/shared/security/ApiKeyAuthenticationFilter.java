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
            verifier.authenticate(presented).ifPresent(principal ->
                    SecurityContextHolder.getContext()
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
