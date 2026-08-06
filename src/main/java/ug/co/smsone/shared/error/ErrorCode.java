package ug.co.smsone.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error codes. The enum name is the wire {@code code} — clients switch on it,
 * so renaming an entry is a breaking API change.
 */
public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    ACCOUNT_NOT_PROVISIONED(HttpStatus.FORBIDDEN, "Account not provisioned"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Account disabled"),
    SUBSCRIPTION_PAUSED(HttpStatus.PAYMENT_REQUIRED, "Subscription paused"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus httpStatus;
    private final String title;

    ErrorCode(HttpStatus httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }

    public String code() {
        return name();
    }
}
