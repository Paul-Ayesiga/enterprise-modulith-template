package ug.co.smsone.gateway.filter;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * W3C Trace Context propagation. A caller's valid {@code traceparent} is adopted (its trace id is kept,
 * so the edge joins the caller's trace); otherwise a new trace is started. The gateway forwards a
 * {@code traceparent} carrying that trace id and a fresh span id downstream, so the backend continues
 * the SAME trace — one id spans gateway → service, ready for a tracer/collector in the modulith. The
 * trace id is stored on the exchange (for the access log) and echoed as {@code X-Trace-Id} so a client
 * can correlate its request. The gateway records no spans itself (stateless, ADR 0007) — it propagates.
 */
@Component
public class TraceContextGlobalFilter implements GlobalFilter, Ordered {

    public static final String TRACEPARENT = "traceparent";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String VERSION = "00";
    private static final String SAMPLED = "01";
    private static final String INVALID_TRACE_ID = "0".repeat(32);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = traceIdFrom(exchange.getRequest().getHeaders().getFirst(TRACEPARENT));
        String traceparent = VERSION + "-" + traceId + "-" + randomHex(16) + "-" + SAMPLED;

        GatewayAttributes.putTraceId(exchange, traceId);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
            return Mono.empty();
        });
        ServerWebExchange mutated = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(TRACEPARENT, traceparent)))
                .build();
        return chain.filter(mutated);
    }

    /** The 32-hex trace id from a well-formed, non-zero W3C traceparent, or a freshly minted one. */
    private static String traceIdFrom(String traceparent) {
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 4 && parts[1].length() == 32 && isHex(parts[1])
                    && !parts[1].equals(INVALID_TRACE_ID)) {
                return parts[1];
            }
        }
        return randomHex(32);
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** {@code length} hex chars (16 = span, 32 = trace) from random longs, formatted unsigned. */
    private static String randomHex(int length) {
        long high = ThreadLocalRandom.current().nextLong();
        if (length == 16) {
            return String.format("%016x", high);
        }
        return String.format("%016x%016x", high, ThreadLocalRandom.current().nextLong());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // right after the request id, before the access log
    }
}
