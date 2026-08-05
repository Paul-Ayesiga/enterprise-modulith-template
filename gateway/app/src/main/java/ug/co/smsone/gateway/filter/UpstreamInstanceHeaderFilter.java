package ug.co.smsone.gateway.filter;

import java.net.URI;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Stamps {@code X-Gateway-Upstream: host:port} — the backend instance the request was actually routed
 * to. For a load-balanced ({@code lb://}) service that is the instance Spring Cloud LoadBalancer chose,
 * so the header makes balancing and failover visible: {@code curl -i} through the edge shows requests
 * spreading across instances, and only the survivors after one is killed. Useful upstream observability
 * beyond the demo. Runs last (lowest precedence) so the LB filter has already resolved the target URL
 * into {@code GATEWAY_REQUEST_URL_ATTR}; written in {@code beforeCommit} so it reflects the final target
 * even after a retry moved the request to another instance.
 */
@Component
class UpstreamInstanceHeaderFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Gateway-Upstream";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            if (target != null && target.getHost() != null) {
                int port = target.getPort() != -1 ? target.getPort()
                        : "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
                exchange.getResponse().getHeaders().set(HEADER, target.getHost() + ":" + port);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
