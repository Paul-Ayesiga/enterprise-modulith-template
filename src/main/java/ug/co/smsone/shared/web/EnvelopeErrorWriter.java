package ug.co.smsone.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import ug.co.smsone.shared.error.ErrorCode;

/** Writes an envelope error straight to the servlet response — for filters and security handlers
 * that run outside MVC (no advice, no exception handler). */
@Component
public class EnvelopeErrorWriter {

    private final ObjectMapper objectMapper;
    private final ApiMetaFactory metaFactory;

    public EnvelopeErrorWriter(ObjectMapper objectMapper, ApiMetaFactory metaFactory) {
        this.objectMapper = objectMapper;
        this.metaFactory = metaFactory;
    }

    public void write(HttpServletResponse response, ErrorCode errorCode, String detail, ApiSource source)
            throws IOException {
        ApiMeta meta = metaFactory.create();
        ApiError error = new ApiError(
                meta.requestId() + "-1",
                String.valueOf(errorCode.httpStatus().value()),
                errorCode.code(),
                errorCode.title(),
                detail,
                source);
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.errors(List.of(error), meta)));
    }
}
