package ug.co.smsone.shared.error;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ug.co.smsone.shared.web.ApiError;
import ug.co.smsone.shared.web.ApiMeta;
import ug.co.smsone.shared.web.ApiMetaFactory;
import ug.co.smsone.shared.web.ApiResponse;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Translates every failure into the envelope — or, when the client sends
 * {@code Accept: application/problem+json}, into RFC 9457 Problem Details (same data, standard
 * shape). The catch-all 500 is the only place a stack trace is logged.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private final ApiMetaFactory metaFactory;

    public GlobalExceptionHandler(ApiMetaFactory metaFactory) {
        this.metaFactory = metaFactory;
    }

    // --- Bean validation on @RequestBody: multi-error 422 ---

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ApiMeta meta = metaFactory.create();
        AtomicInteger index = new AtomicInteger(1);
        List<ApiError> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(error(meta, index, ErrorCode.VALIDATION_FAILED,
                    validationCode(fieldError.getCode()),
                    fieldError.getDefaultMessage(),
                    ApiSource.pointer("/data/attributes/" + fieldError.getField())));
        }
        ex.getBindingResult().getGlobalErrors().forEach(objectError -> errors.add(
                error(meta, index, ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.code(),
                        objectError.getDefaultMessage(), ApiSource.pointer("/data"))));
        return render(ErrorCode.VALIDATION_FAILED, errors, meta);
    }

    // --- Bean validation on parameters and service-layer validation ---

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        ApiMeta meta = metaFactory.create();
        AtomicInteger index = new AtomicInteger(1);
        List<ApiError> errors = ex.getConstraintViolations().stream()
                .map(violation -> error(meta, index, ErrorCode.VALIDATION_FAILED,
                        ErrorCode.VALIDATION_FAILED.code(), violation.getMessage(),
                        ApiSource.parameter(lastPathNode(violation.getPropertyPath().toString()))))
                .toList();
        return render(ErrorCode.VALIDATION_FAILED, errors, meta);
    }

    // --- Business exceptions ---

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex) {
        ApiMeta meta = metaFactory.create();
        ErrorCode errorCode = ex.errorCode();
        return render(errorCode, List.of(error(meta, new AtomicInteger(1), errorCode,
                errorCode.code(), ex.detail(), ex.source())), meta);
    }

    // --- Method-security denials: must not fall into the 500 catch-all ---

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        ApiMeta meta = metaFactory.create();
        return render(ErrorCode.FORBIDDEN, List.of(error(meta, new AtomicInteger(1),
                ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.code(),
                "You do not have permission to perform this operation.", null)), meta);
    }

    // --- Framework exceptions: base-class handlers funnel through here ---

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ApiMeta meta = metaFactory.create();
        ErrorCode errorCode = mapStatus(HttpStatus.resolve(statusCode.value()));
        String detail = body instanceof ProblemDetail problemDetail && problemDetail.getDetail() != null
                ? problemDetail.getDetail()
                : errorCode.title();
        ApiError error = new ApiError(meta.requestId() + "-1", String.valueOf(statusCode.value()),
                errorCode.code(), errorCode.title(), detail, null);
        return render(statusCode, errorCode, List.of(error), meta, headers);
    }

    // --- Catch-all: fixed safe 500; the ONLY place the stack trace is logged ---

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        ApiMeta meta = metaFactory.create();
        log.error("Unhandled exception [requestId={}]", meta.requestId(), ex);
        return render(ErrorCode.INTERNAL_ERROR, List.of(error(meta, new AtomicInteger(1),
                ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.code(),
                "An unexpected error occurred. Contact support with the request id.", null)), meta);
    }

    // --- Rendering: envelope by default, RFC 9457 when the client asks for problem+json ---

    private ResponseEntity<Object> render(ErrorCode errorCode, List<ApiError> errors, ApiMeta meta) {
        return render(errorCode.httpStatus(), errorCode, errors, meta, null);
    }

    private ResponseEntity<Object> render(HttpStatusCode status, ErrorCode errorCode,
            List<ApiError> errors, ApiMeta meta, HttpHeaders headers) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (headers != null) {
            builder.headers(headers); // keep framework-supplied headers (e.g. Allow on 405)
        }
        if (problemJsonRequested()) {
            ProblemDetail problem = ProblemDetail.forStatus(status);
            problem.setTitle(errorCode.title());
            problem.setDetail(errors.size() == 1
                    ? errors.getFirst().detail()
                    : errors.size() + " validation errors — see the errors extension.");
            problem.setProperty("code", errorCode.code());
            problem.setProperty("requestId", meta.requestId());
            if (errors.size() > 1 || errors.getFirst().source() != null) {
                problem.setProperty("errors", errors); // pointers survive even for a single error
            }
            return builder.contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(ApiResponse.errors(errors, meta));
    }

    private static boolean problemJsonRequested() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        String accept = attributes.getRequest().getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    private static ApiError error(ApiMeta meta, AtomicInteger index, ErrorCode errorCode,
            String code, String detail, ApiSource source) {
        return new ApiError(
                meta.requestId() + "-" + index.getAndIncrement(),
                String.valueOf(errorCode.httpStatus().value()),
                code,
                errorCode.title(),
                detail,
                source);
    }

    private static String validationCode(String constraintCode) {
        if (constraintCode == null) {
            return ErrorCode.VALIDATION_FAILED.code();
        }
        return "VALIDATION_" + CAMEL_BOUNDARY.matcher(constraintCode).replaceAll("_").toUpperCase();
    }

    private static String lastPathNode(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }

    private static ErrorCode mapStatus(HttpStatus status) {
        if (status == null) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (status) {
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case UNPROCESSABLE_ENTITY -> ErrorCode.VALIDATION_FAILED;
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case CONFLICT -> ErrorCode.CONFLICT;
            default -> status.is4xxClientError() ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }
}
