package ug.co.smsone.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;

/**
 * Renders 403 as the envelope — security failures look like every other API error.
 *
 * <p>Reached only for denials raised by the security chain itself ({@code @Order -100}) — the
 * {@code /mcp} door policy is the live one — because a {@code @PreAuthorize} denial inside the
 * dispatcher is answered by {@code GlobalExceptionHandler} instead. So there is never a tenant axis
 * on the thread here, and the write declares its own; {@link PlatformAxisErrors} says why a 403 needs
 * one at all.
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final EnvelopeErrorWriter errorWriter;

    public ApiAccessDeniedHandler(EnvelopeErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        PlatformAxisErrors.write(errorWriter, request, response, ErrorCode.FORBIDDEN,
                "You do not have permission to access this resource.", null);
    }
}
