package ug.co.smsone.gateway.route;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.config.GatewayProperties;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteRegistrar;
import ug.co.smsone.gateway.core.route.RouteSource;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistry;
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.core.traffic.TrafficPolicy;
import ug.co.smsone.gateway.core.transform.TransformPolicy;

/**
 * The route/service catalog: seeds the route table and service registry from {@code gateway.*} config
 * at startup, then stands as the live, MUTABLE route table (the {@link RouteSource}/{@link
 * ServiceRegistry}/{@link RouteRegistrar} ports). {@code register}/{@code remove} change routes at
 * runtime and publish a {@link RefreshRoutesEvent}, so the runtime re-reads and the edge re-applies
 * policies — a route takes effect with no restart. Services stay config-only. Routes read out sorted by
 * {@code order} (lower = higher priority).
 */
@Component
class RouteCatalog implements RouteSource, ServiceRegistry, RouteRegistrar {

    private final Map<String, ServiceDefinition> services;
    private final ConcurrentMap<String, RouteDefinition> routes = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;

    RouteCatalog(GatewayProperties properties, ApplicationEventPublisher events) {
        this.events = events;
        this.services = properties.services().stream()
                .map(service -> new ServiceDefinition(service.id(), service.effectiveInstances(), service.healthPath()))
                .collect(Collectors.toUnmodifiableMap(ServiceDefinition::id, Function.identity()));
        properties.routes().forEach(route -> routes.put(route.id(), toRouteDefinition(route)));
    }

    @Override
    public void register(RouteDefinition route) {
        routes.put(route.id(), route);
        events.publishEvent(new RefreshRoutesEvent(this));
    }

    @Override
    public void remove(String routeId) {
        if (routes.remove(routeId) != null) {
            events.publishEvent(new RefreshRoutesEvent(this));
        }
    }

    @Override
    public List<RouteDefinition> routes() {
        return routes.values().stream()
                .sorted(Comparator.comparingInt(RouteDefinition::order))
                .toList();
    }

    @Override
    public Optional<ServiceDefinition> find(String serviceId) {
        return Optional.ofNullable(services.get(serviceId));
    }

    @Override
    public List<ServiceDefinition> all() {
        return List.copyOf(services.values());
    }

    private static RouteDefinition toRouteDefinition(GatewayProperties.RouteProps route) {
        return new RouteDefinition(route.id(), route.order(),
                route.predicates().stream()
                        .map(predicate -> new RoutePredicate(predicate.kind(), predicate.args()))
                        .toList(),
                route.serviceId(), toAuthPolicy(route.auth()), toTrafficPolicy(route.traffic()),
                toTransformPolicy(route.transform()), Map.of());
    }

    private static TransformPolicy toTransformPolicy(GatewayProperties.TransformProps transform) {
        return transform == null ? TransformPolicy.NONE
                : new TransformPolicy(transform.rewritePathRegex(), transform.rewritePathReplacement(),
                        transform.stripPrefix(), transform.setRequestHeaders(), transform.removeRequestHeaders(),
                        transform.addRequestParams(), transform.setResponseHeaders(),
                        transform.removeResponseHeaders());
    }

    private static AuthPolicy toAuthPolicy(GatewayProperties.AuthProps auth) {
        return auth == null ? AuthPolicy.OPEN
                : new AuthPolicy(auth.authenticated(), Set.copyOf(auth.scopes()), auth.tenantPathTemplate());
    }

    private static TrafficPolicy toTrafficPolicy(GatewayProperties.TrafficProps traffic) {
        return traffic == null ? TrafficPolicy.NONE
                : new TrafficPolicy(traffic.responseTimeoutMs(), traffic.maxRequestBytes(),
                        traffic.rateLimited(), traffic.circuitBreaker(), traffic.retries(),
                        traffic.cacheTtlSeconds());
    }
}
