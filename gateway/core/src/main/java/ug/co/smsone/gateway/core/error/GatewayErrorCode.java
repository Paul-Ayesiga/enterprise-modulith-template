package ug.co.smsone.gateway.core.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing EDGE error codes — the enum name is the wire {@code code}, distinct from any
 * backend error, mirroring the platform's convention (renaming an entry is a breaking API change).
 * The gateway's own failures speak this vocabulary; a backend's own error body passes through untouched.
 */
public enum GatewayErrorCode {

    NO_ROUTE(HttpStatus.NOT_FOUND),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    BAD_GATEWAY(HttpStatus.BAD_GATEWAY),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT);

    private final HttpStatus status;

    GatewayErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return name();
    }
}
