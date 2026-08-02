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
        GatewayErrorCode code = map(ex);
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
        byte[] body = envelope(code, GatewayAttributes.requestId(exchange));
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private static GatewayErrorCode map(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return switch (rse.getStatusCode().value()) {
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
        }
        if (ex instanceof TimeoutException) {
            return GatewayErrorCode.GATEWAY_TIMEOUT;
        }
        if (ex instanceof ConnectException) {
            return GatewayErrorCode.BAD_GATEWAY;
        }
        return GatewayErrorCode.BAD_GATEWAY; // most non-status errors at the edge are upstream faults
    }

    private static byte[] envelope(GatewayErrorCode code, String requestId) {
        StringBuilder json = new StringBuilder(112)
                .append("{\"errors\":[{\"code\":\"").append(code.code())
                .append("\",\"detail\":\"").append(escape(detail(code))).append('"');
        if (requestId != null) {
            json.append(",\"requestId\":\"").append(escape(requestId)).append('"');
        }
        json.append("}]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String detail(GatewayErrorCode code) {
        return switch (code) {
            case NO_ROUTE -> "No route matched the request.";
            case BAD_GATEWAY -> "The upstream service could not be reached.";
            case GATEWAY_TIMEOUT -> "The upstream service did not respond in time.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable.";
            default -> code.code();
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
