package ug.co.smsone.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;

/**
 * Renders 401 as the envelope — security failures look like every other API error. On the MCP
 * surface the 401 additionally carries the RFC 9728 challenge ({@code WWW-Authenticate} naming the
 * protected-resource metadata URL) so a native OAuth connector can bootstrap its consent flow from
 * the refusal itself.
 *
 * <p>Invoked from inside the security chain ({@code @Order -100}), so no tenant axis exists yet and
 * the write declares its own — {@link PlatformAxisErrors} has the reason a 401 needs one.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final EnvelopeErrorWriter errorWriter;
    private final String mcpChallenge;

    public ApiAuthenticationEntryPoint(EnvelopeErrorWriter errorWriter,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.mcp.public-base-url:http://localhost:28090}") String mcpPublicBaseUrl) {
        this.errorWriter = errorWriter;
        this.mcpChallenge =
                "Bearer resource_metadata=\"" + mcpPublicBaseUrl + "/.well-known/oauth-protected-resource\"";
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        String path = request.getRequestURI();
        if (path.equals("/mcp") || path.startsWith("/mcp/")) {
            response.setHeader("WWW-Authenticate", mcpChallenge);
        }
        PlatformAxisErrors.write(errorWriter, request, response, ErrorCode.UNAUTHORIZED,
                "A valid bearer token is required to access this resource.", null);
    }
}
