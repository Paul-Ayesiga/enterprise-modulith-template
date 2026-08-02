package ug.co.smsone.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * The access log — one line per request with method, path, status, latency, and the request id. It
 * wraps the whole chain (runs just after request-id) so the timing spans routing + backend. Logged to
 * a dedicated {@code gateway.access} logger so it stays separable from gateway/system logs.
 */
@Component
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger("gateway.access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("{} {} -> {} {}ms rid={} trace={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getRawPath(),
                    exchange.getResponse().getStatusCode(),
                    millis,
                    GatewayAttributes.requestId(exchange),
                    GatewayAttributes.traceId(exchange));
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2; // after request-id (+0) and trace-context (+1)
    }
}
