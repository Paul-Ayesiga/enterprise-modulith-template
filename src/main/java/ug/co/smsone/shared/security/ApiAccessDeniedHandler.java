package ug.co.smsone.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;

/** Renders 403 as the envelope — security failures look like every other API error. */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final EnvelopeErrorWriter errorWriter;

    public ApiAccessDeniedHandler(EnvelopeErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        errorWriter.write(response, ErrorCode.FORBIDDEN,
                "You do not have permission to access this resource.", null);
    }
}
