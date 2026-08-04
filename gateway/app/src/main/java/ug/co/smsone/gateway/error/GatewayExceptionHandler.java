package ug.co.smsone.gateway.error;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.error.GatewayErrorCode;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * One error envelope for every gateway-generated failure, distinct from a backend's own error body:
 * {@code {"errors":[{"code","detail","requestId"}]}} with a stable {@link GatewayErrorCode}. Ordered
 * ahead of Boot's default handler ({@code -1}). A no-route is 404 {@code NO_ROUTE}; an unreachable or
 * slow backend is 502/504. If the response already committed (the backend streamed), it propagates.
 * The envelope is a fixed shape, so it is written directly — no serializer on the edge's hot path.
 */
@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {

    /** The gateway/system error stream — upstream faults, separable from access/security logs. */
    private static final Logger errorLog = LoggerFactory.getLogger("gateway.error");

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        Rendered rendered = render(ex);
        GatewayErrorCode code = rendered.code();
        if (code.status().is5xxServerError()) {
            // An upstream fault (bad gateway / timeout / unavailable) — the operator's concern, not the caller's.
            errorLog.warn("gateway_fault code={} status={} method={} path={} rid={} cause={}",
                    code.code(), code.status().value(),
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getRawPath(),
                    GatewayAttributes.requestId(exchange), ex.toString());
        }
        exchange.getResponse().setStatusCode(code.status());
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = envelope(code, rendered.detail(), GatewayAttributes.requestId(exchange));
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private record Rendered(GatewayErrorCode code, String detail) {
    }

    /**
     * Map the throwable to an edge error code AND a caller-facing detail. When a filter threw a
     * {@link ResponseStatusException} with a reason (auth's "Missing scope: …", the blocklist's
     * "Source address blocked", lifecycle's "This route has been retired …"), that reason IS the
     * detail — a filter knows more than a status code does. Otherwise a per-code default explains it.
     */
    private static Rendered render(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            GatewayErrorCode code = switch (rse.getStatusCode().value()) {
                case 401 -> GatewayErrorCode.UNAUTHORIZED;
                case 403 -> GatewayErrorCode.FORBIDDEN;
                case 404 -> GatewayErrorCode.NO_ROUTE;
                case 410 -> GatewayErrorCode.GONE;
                case 413 -> GatewayErrorCode.PAYLOAD_TOO_LARGE;
                case 429 -> GatewayErrorCode.RATE_LIMITED;
                case 503 -> GatewayErrorCode.SERVICE_UNAVAILABLE;
                case 504 -> GatewayErrorCode.GATEWAY_TIMEOUT;
                default -> GatewayErrorCode.BAD_GATEWAY;
            };
            String reason = rse.getReason();
            return new Rendered(code, reason == null || reason.isBlank() ? defaultDetail(code) : reason);
        }
        if (ex instanceof TimeoutException) {
            return new Rendered(GatewayErrorCode.GATEWAY_TIMEOUT, defaultDetail(GatewayErrorCode.GATEWAY_TIMEOUT));
        }
        if (ex instanceof ConnectException) {
            return new Rendered(GatewayErrorCode.BAD_GATEWAY, defaultDetail(GatewayErrorCode.BAD_GATEWAY));
        }
        // Most non-status errors at the edge are upstream faults.
        return new Rendered(GatewayErrorCode.BAD_GATEWAY, defaultDetail(GatewayErrorCode.BAD_GATEWAY));
    }

    private static byte[] envelope(GatewayErrorCode code, String detail, String requestId) {
        StringBuilder json = new StringBuilder(160)
                .append("{\"errors\":[{\"code\":\"").append(code.code())
                .append("\",\"detail\":\"").append(escape(detail)).append('"');
        if (requestId != null) {
            json.append(",\"requestId\":\"").append(escape(requestId)).append('"');
        }
        json.append("}]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The fallback detail when a filter didn't supply a reason — never just the bare code name. */
    private static String defaultDetail(GatewayErrorCode code) {
        return switch (code) {
            case NO_ROUTE -> "No route at the gateway matched this request.";
            case UNAUTHORIZED -> "Authentication is required to reach this route.";
            case FORBIDDEN -> "You are not permitted to use this route.";
            case GONE -> "This API route has been retired at the gateway and is no longer served.";
            case PAYLOAD_TOO_LARGE -> "The request body exceeds the gateway's size limit.";
            case RATE_LIMITED -> "Too many requests — slow down and retry after a moment.";
            case BAD_GATEWAY -> "The upstream service could not be reached.";
            case GATEWAY_TIMEOUT -> "The upstream service did not respond in time.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable.";
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
