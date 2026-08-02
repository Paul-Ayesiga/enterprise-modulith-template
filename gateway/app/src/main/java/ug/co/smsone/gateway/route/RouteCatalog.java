package ug.co.smsone.gateway.route;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.config.GatewayProperties;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteRegistrar;
import ug.co.smsone.gateway.core.route.RouteSource;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistrar;
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
class RouteCatalog implements RouteSource, ServiceRegistry, RouteRegistrar, ServiceRegistrar {

    private final ConcurrentMap<String, ServiceDefinition> services = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RouteDefinition> routes = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;

    RouteCatalog(GatewayProperties properties, ApplicationEventPublisher events) {
        this.events = events;
        properties.services().forEach(service -> services.put(service.id(),
                new ServiceDefinition(service.id(), service.effectiveInstances(), service.healthPath())));
        properties.routes().forEach(route -> routes.put(route.id(), toRouteDefinition(route, properties.policies())));
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
    public void register(ServiceDefinition service) {
        services.put(service.id(), service);
        events.publishEvent(new RefreshRoutesEvent(this));
    }

    @Override
    public void deregister(String serviceId) {
        if (services.remove(serviceId) != null) {
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

    private static RouteDefinition toRouteDefinition(GatewayProperties.RouteProps route,
            Map<String, GatewayProperties.PolicyProps> policies) {
        // A route's inline config wins per aspect; otherwise it inherits its referenced named policy.
        GatewayProperties.PolicyProps policy = resolvePolicy(route.policyRef(), policies);
        GatewayProperties.AuthProps auth = route.auth() != null ? route.auth() : policyAuth(policy);
        GatewayProperties.TrafficProps traffic = route.traffic() != null ? route.traffic() : policyTraffic(policy);
        GatewayProperties.TransformProps transform =
                route.transform() != null ? route.transform() : policyTransform(policy);
        return new RouteDefinition(route.id(), route.order(),
                route.predicates().stream()
                        .map(predicate -> new RoutePredicate(predicate.kind(), predicate.args()))
                        .toList(),
                route.serviceId(), toAuthPolicy(auth), toTrafficPolicy(traffic),
                toTransformPolicy(transform), Map.of());
    }

    private static GatewayProperties.PolicyProps resolvePolicy(String ref,
            Map<String, GatewayProperties.PolicyProps> policies) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        GatewayProperties.PolicyProps policy = policies.get(ref);
        if (policy == null) {
            throw new IllegalStateException("Route references unknown policy '" + ref + "'");
        }
        return policy;
    }

    private static GatewayProperties.AuthProps policyAuth(GatewayProperties.PolicyProps policy) {
        return policy == null ? null : policy.auth();
    }

    private static GatewayProperties.TrafficProps policyTraffic(GatewayProperties.PolicyProps policy) {
        return policy == null ? null : policy.traffic();
    }

    private static GatewayProperties.TransformProps policyTransform(GatewayProperties.PolicyProps policy) {
        return policy == null ? null : policy.transform();
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
