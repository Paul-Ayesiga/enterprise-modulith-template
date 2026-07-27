package ug.co.smsone.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;

/** Renders 401 as the envelope — security failures look like every other API error. */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final EnvelopeErrorWriter errorWriter;

    public ApiAuthenticationEntryPoint(EnvelopeErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        errorWriter.write(response, ErrorCode.UNAUTHORIZED,
                "A valid bearer token is required to access this resource.", null);
    }
}
