package ug.co.smsone.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.ApiError;
import ug.co.smsone.shared.web.ApiMeta;
import ug.co.smsone.shared.web.ApiMetaFactory;
import ug.co.smsone.shared.web.ApiResponse;

/** Renders 403 as the envelope — security failures look like every other API error. */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final ApiMetaFactory metaFactory;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper, ApiMetaFactory metaFactory) {
        this.objectMapper = objectMapper;
        this.metaFactory = metaFactory;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        ApiMeta meta = metaFactory.create();
        ApiError error = new ApiError(
                meta.requestId() + "-1",
                String.valueOf(ErrorCode.FORBIDDEN.httpStatus().value()),
                ErrorCode.FORBIDDEN.code(),
                ErrorCode.FORBIDDEN.title(),
                "You do not have permission to access this resource.",
                null);
        response.setStatus(ErrorCode.FORBIDDEN.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.errors(List.of(error), meta)));
    }
}
