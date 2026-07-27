package ug.co.smsone.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.ApiError;
import ug.co.smsone.shared.web.ApiMeta;
import ug.co.smsone.shared.web.ApiMetaFactory;
import ug.co.smsone.shared.web.ApiResponse;

/** Renders 401 as the envelope — security failures look like every other API error. */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final ApiMetaFactory metaFactory;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper, ApiMetaFactory metaFactory) {
        this.objectMapper = objectMapper;
        this.metaFactory = metaFactory;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ApiMeta meta = metaFactory.create();
        ApiError error = new ApiError(
                meta.requestId() + "-1",
                String.valueOf(ErrorCode.UNAUTHORIZED.httpStatus().value()),
                ErrorCode.UNAUTHORIZED.code(),
                ErrorCode.UNAUTHORIZED.title(),
                "A valid bearer token is required to access this resource.",
                null);
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.errors(List.of(error), meta)));
    }
}
