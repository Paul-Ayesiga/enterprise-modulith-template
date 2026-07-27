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
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ug.co.smsone.shared.web.ApiError;
import ug.co.smsone.shared.web.ApiMeta;
import ug.co.smsone.shared.web.ApiMetaFactory;
import ug.co.smsone.shared.web.ApiResponse;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Translates every failure into the envelope: framework exceptions (via
 * {@link #handleExceptionInternal}), bean-validation failures (multi-error 422), the
 * {@link ApiException} hierarchy, and a catch-all 500 — the only place a stack trace is logged.
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
            errors.add(new ApiError(
                    errorId(meta, index),
                    String.valueOf(ErrorCode.VALIDATION_FAILED.httpStatus().value()),
                    validationCode(fieldError.getCode()),
                    ErrorCode.VALIDATION_FAILED.title(),
                    fieldError.getDefaultMessage(),
                    ApiSource.pointer("/data/attributes/" + fieldError.getField())));
        }
        ex.getBindingResult().getGlobalErrors().forEach(objectError -> errors.add(new ApiError(
                errorId(meta, index),
                String.valueOf(ErrorCode.VALIDATION_FAILED.httpStatus().value()),
                ErrorCode.VALIDATION_FAILED.code(),
                ErrorCode.VALIDATION_FAILED.title(),
                objectError.getDefaultMessage(),
                ApiSource.pointer("/data"))));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.errors(errors, meta));
    }

    // --- Bean validation on @RequestParam / @PathVariable and service-layer validation ---

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        ApiMeta meta = metaFactory.create();
        AtomicInteger index = new AtomicInteger(1);
        List<ApiError> errors = ex.getConstraintViolations().stream()
                .map(violation -> new ApiError(
                        errorId(meta, index),
                        String.valueOf(ErrorCode.VALIDATION_FAILED.httpStatus().value()),
                        ErrorCode.VALIDATION_FAILED.code(),
                        ErrorCode.VALIDATION_FAILED.title(),
                        violation.getMessage(),
                        ApiSource.parameter(lastPathNode(violation.getPropertyPath().toString()))))
                .map(ApiError.class::cast)
                .toList();
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.errors(errors, meta));
    }

    // --- Business exceptions ---

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        ApiMeta meta = metaFactory.create();
        ErrorCode errorCode = ex.errorCode();
        ApiError error = new ApiError(
                meta.requestId() + "-1",
                String.valueOf(errorCode.httpStatus().value()),
                errorCode.code(),
                errorCode.title(),
                ex.detail(),
                ex.source());
        return ResponseEntity.status(errorCode.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.errors(List.of(error), meta));
    }

    // --- Framework exceptions: base-class handlers funnel through here; translate to envelope ---

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ApiMeta meta = metaFactory.create();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        ErrorCode errorCode = mapStatus(status);
        String detail = body instanceof ProblemDetail problemDetail && problemDetail.getDetail() != null
                ? problemDetail.getDetail()
                : errorCode.title();
        ApiError error = new ApiError(
                meta.requestId() + "-1",
                String.valueOf(statusCode.value()),
                errorCode.code(),
                errorCode.title(),
                detail,
                null);
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.errors(List.of(error), meta));
    }

    // --- Method-security denials: must not fall into the 500 catch-all ---

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        ApiMeta meta = metaFactory.create();
        ApiError error = new ApiError(
                meta.requestId() + "-1",
                String.valueOf(ErrorCode.FORBIDDEN.httpStatus().value()),
                ErrorCode.FORBIDDEN.code(),
                ErrorCode.FORBIDDEN.title(),
                "You do not have permission to perform this operation.",
                null);
        return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.errors(List.of(error), meta));
    }

    // --- Catch-all: fixed safe 500; the ONLY place the stack trace is logged ---

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        ApiMeta meta = metaFactory.create();
        log.error("Unhandled exception [requestId={}]", meta.requestId(), ex);
        ApiError error = new ApiError(
                meta.requestId() + "-1",
                String.valueOf(ErrorCode.INTERNAL_ERROR.httpStatus().value()),
                ErrorCode.INTERNAL_ERROR.code(),
                ErrorCode.INTERNAL_ERROR.title(),
                "An unexpected error occurred. Contact support with the request id.",
                null);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.errors(List.of(error), meta));
    }

    private static String errorId(ApiMeta meta, AtomicInteger index) {
        return meta.requestId() + "-" + index.getAndIncrement();
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
