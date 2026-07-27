package ug.co.smsone.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import ug.co.smsone.shared.error.ErrorCode;

/**
 * Writes an error straight to the servlet response — for filters and security handlers that run
 * outside MVC. Honors the same content negotiation as the MVC handler: envelope by default,
 * RFC 9457 problem+json when the client asks.
 */
@Component
public class EnvelopeErrorWriter {

    private final ObjectMapper objectMapper;
    private final ApiMetaFactory metaFactory;

    public EnvelopeErrorWriter(ObjectMapper objectMapper, ApiMetaFactory metaFactory) {
        this.objectMapper = objectMapper;
        this.metaFactory = metaFactory;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode,
            String detail, ApiSource source) throws IOException {
        ApiMeta meta = metaFactory.create();
        response.setStatus(errorCode.httpStatus().value());

        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE)) {
            ProblemDetail problem = ProblemDetail.forStatus(errorCode.httpStatus());
            problem.setTitle(errorCode.title());
            problem.setDetail(detail);
            problem.setProperty("code", errorCode.code());
            problem.setProperty("requestId", meta.requestId());
            if (source != null) {
                problem.setProperty("source", source);
            }
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(problem));
            return;
        }

        ApiError error = new ApiError(
                meta.requestId() + "-1",
                String.valueOf(errorCode.httpStatus().value()),
                errorCode.code(),
                errorCode.title(),
                detail,
                source);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.errors(List.of(error), meta)));
    }
}
