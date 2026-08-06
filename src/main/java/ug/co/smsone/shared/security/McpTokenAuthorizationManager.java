package ug.co.smsone.shared.security;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code /mcp} door policy: exactly two credentials enter. An org API key (the machine path,
 * unchanged), or an OAuth JWT minted FOR this surface — its {@code aud} must carry the MCP audience,
 * which only the consent flow's {@code mcp} client scope stamps. A web login token is a perfectly
 * valid realm JWT and is still refused here: neither surface's tokens work on the other, so a stolen
 * browser session can never drive agent tooling and an MCP token can't ride the web app.
 */
@Component
public class McpTokenAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final String audience;

    public McpTokenAuthorizationManager(@Value("${app.mcp.audience:smsone-mcp}") String audience) {
        this.audience = audience;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        Authentication caller = authentication.get();
        if (caller instanceof ApiKeyAuthenticationToken) {
            return new AuthorizationDecision(true);
        }
        if (caller instanceof JwtAuthenticationToken jwt) {
            List<String> audiences = jwt.getToken().getAudience();
            return new AuthorizationDecision(audiences != null && audiences.contains(audience));
        }
        return new AuthorizationDecision(false); // anonymous → the entry point's 401 + challenge
    }
}
