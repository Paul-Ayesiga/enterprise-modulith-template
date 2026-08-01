package ug.co.smsone.gateway.filter;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * The first pipeline stage: every request gets a stable id. An incoming {@code X-Request-Id} is
 * honored (so a caller's correlation survives the edge); otherwise one is minted. The id is stored on
 * the exchange, propagated to the backend, and echoed on the response — the modulith's
 * {@code X-Request-Id} convention, now originating at the edge.
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID = "X-Request-Id";
    public static final String CORRELATION_ID = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = orMint(exchange.getRequest().getHeaders().getFirst(REQUEST_ID));
        String correlationId = orElse(exchange.getRequest().getHeaders().getFirst(CORRELATION_ID), requestId);
        GatewayAttributes.putRequestId(exchange, requestId);
        GatewayAttributes.putCorrelationId(exchange, correlationId);
        // Echo on the response no matter what the backend does (set right before commit).
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(REQUEST_ID, requestId);
            return Mono.empty();
        });
        // Propagate downstream.
        ServerWebExchange mutated = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set(REQUEST_ID, requestId);
                    headers.set(CORRELATION_ID, correlationId);
                }))
                .build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static String orMint(String value) {
        return value == null || value.isBlank() ? "gw-" + UUID.randomUUID() : value;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
