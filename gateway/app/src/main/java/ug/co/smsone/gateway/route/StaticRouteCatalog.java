package ug.co.smsone.gateway.route;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.config.GatewayProperties;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteSource;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistry;
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.core.traffic.TrafficPolicy;

/**
 * The static-config adapter for the {@link RouteSource} and {@link ServiceRegistry} ports: it maps
 * {@code gateway.*} properties into the core's model, once, at startup. Routes come out sorted by
 * {@code order} (lower = higher priority).
 */
@Component
class StaticRouteCatalog implements RouteSource, ServiceRegistry {

    private final List<RouteDefinition> routes;
    private final Map<String, ServiceDefinition> services;

    StaticRouteCatalog(GatewayProperties properties) {
        this.services = properties.services().stream()
                .map(service -> new ServiceDefinition(service.id(), service.uri(), service.healthPath()))
                .collect(Collectors.toUnmodifiableMap(ServiceDefinition::id, Function.identity()));
        this.routes = properties.routes().stream()
                .sorted(Comparator.comparingInt(GatewayProperties.RouteProps::order))
                .map(route -> new RouteDefinition(route.id(), route.order(),
                        route.predicates().stream()
                                .map(predicate -> new RoutePredicate(predicate.kind(), predicate.args()))
                                .toList(),
                        route.serviceId(), toAuthPolicy(route.auth()), toTrafficPolicy(route.traffic()), Map.of()))
                .toList();
    }

    private static AuthPolicy toAuthPolicy(GatewayProperties.AuthProps auth) {
        return auth == null ? AuthPolicy.OPEN
                : new AuthPolicy(auth.authenticated(), Set.copyOf(auth.scopes()), auth.tenantPathTemplate());
    }

    private static TrafficPolicy toTrafficPolicy(GatewayProperties.TrafficProps traffic) {
        return traffic == null ? TrafficPolicy.NONE
                : new TrafficPolicy(traffic.responseTimeoutMs(), traffic.maxRequestBytes(),
                        traffic.rateLimited(), traffic.circuitBreaker());
    }

    @Override
    public List<RouteDefinition> routes() {
        return routes;
    }

    @Override
    public Optional<ServiceDefinition> find(String serviceId) {
        return Optional.ofNullable(services.get(serviceId));
    }

    @Override
    public List<ServiceDefinition> all() {
        return List.copyOf(services.values());
    }
}
