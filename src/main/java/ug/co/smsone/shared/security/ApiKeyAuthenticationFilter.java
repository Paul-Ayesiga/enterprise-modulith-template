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
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final ObjectProvider<ApiKeyAuthenticator> authenticator;

    public ApiKeyAuthenticationFilter(ObjectProvider<ApiKeyAuthenticator> authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        ApiKeyAuthenticator verifier = authenticator.getIfAvailable();
        if (presented != null && !presented.isBlank() && verifier != null) {
            verifier.authenticate(presented.trim()).ifPresent(principal ->
                    SecurityContextHolder.getContext()
                            .setAuthentication(new ApiKeyAuthenticationToken(principal)));
        }
        chain.doFilter(request, response);
    }
}
