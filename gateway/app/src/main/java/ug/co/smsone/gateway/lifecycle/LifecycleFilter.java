package ug.co.smsone.gateway.lifecycle;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.lifecycle.LifecyclePolicy;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RouteSource;

/**
 * Enforces each route's lifecycle. A RETIRED route no longer proxies — the edge answers 410 Gone. A
 * DEPRECATED route still proxies, but its response carries {@code Deprecation: true} and (if set) a
 * {@code Sunset} date, so callers know to migrate. PUBLISHED routes pass untouched. The policy map is
 * rebuilt on {@link RefreshRoutesEvent}, so a route retired at runtime stops serving without a restart.
 * Runs before edge auth, so a retired version 410s without any auth work.
 */
@Component
public class LifecycleFilter implements GlobalFilter, Ordered, ApplicationListener<RefreshRoutesEvent> {

    private final RouteSource routeSource;
    private volatile Map<String, LifecyclePolicy> lifecycles;

    public LifecycleFilter(RouteSource routeSource) {
        this.routeSource = routeSource;
        this.lifecycles = build();
    }

    @Override
    public void onApplicationEvent(RefreshRoutesEvent event) {
        this.lifecycles = build();
    }

    private Map<String, LifecyclePolicy> build() {
        return routeSource.routes().stream()
                .collect(Collectors.toUnmodifiableMap(RouteDefinition::id, RouteDefinition::lifecycle));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        LifecyclePolicy lifecycle = route == null ? null : lifecycles.get(route.getId());
        if (lifecycle == null) {
            return chain.filter(exchange);
        }
        if (lifecycle.isRetired()) {
            return Mono.error(new ResponseStatusException(HttpStatus.GONE, "This API version is retired."));
        }
        if (lifecycle.isDeprecated()) {
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set("Deprecation", "true");
                if (lifecycle.sunset() != null && !lifecycle.sunset().isBlank()) {
                    exchange.getResponse().getHeaders().set("Sunset", lifecycle.sunset());
                }
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5; // after request-id/trace/access-log, before auth (+10)
    }
}
