package ug.co.smsone.gateway.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.core.lifecycle.LifecyclePolicy;
import ug.co.smsone.gateway.core.lifecycle.RouteLifecycle;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteRegistrar;
import ug.co.smsone.gateway.core.route.RouteSource;
import ug.co.smsone.gateway.core.route.ServiceRegistry;
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.core.traffic.TrafficPolicy;
import ug.co.smsone.gateway.core.transform.TransformPolicy;

/**
 * The route-administration API — a management {@link Endpoint} (id {@code gatewayroutes}), so it is
 * served on the actuator/management port, NOT the public traffic port: bind {@code management.server.port}
 * to a separate port/network and an operator reaches it there while public callers never can. It reads
 * the live route table and creates/deletes routes through the {@link RouteRegistrar}, which refreshes
 * the runtime (and the edge policies) — a route added here takes effect with no restart.
 */
@Component
@Endpoint(id = "gatewayroutes")
public class GatewayRoutesEndpoint {

    private final RouteSource routeSource;
    private final ServiceRegistry services;
    private final RouteRegistrar registrar;

    public GatewayRoutesEndpoint(RouteSource routeSource, ServiceRegistry services, RouteRegistrar registrar) {
        this.routeSource = routeSource;
        this.services = services;
        this.registrar = registrar;
    }

    @ReadOperation
    public List<Map<String, Object>> routes() {
        return routeSource.routes().stream().map(route -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", route.id());
            row.put("order", route.order());
            row.put("serviceId", route.serviceId());
            row.put("authenticated", route.auth().requiresToken());
            row.put("predicates", route.predicates().stream()
                    .map(predicate -> predicate.kind() + " " + predicate.args()).toList());
            row.put("path", firstPath(route));
            row.put("lifecycle", route.lifecycle().status().name());
            return row;
        }).toList();
    }

    /** Create (or replace) an open route matching a path to a known service; takes effect immediately. */
    @WriteOperation
    public Map<String, Object> createRoute(String id, String path, String serviceId) {
        if (services.find(serviceId).isEmpty()) {
            throw new IllegalArgumentException("Unknown service '" + serviceId + "'");
        }
        registrar.register(new RouteDefinition(id, 1000,
                List.of(new RoutePredicate(RoutePredicate.Kind.PATH, List.of(path))),
                serviceId, AuthPolicy.OPEN, TrafficPolicy.NONE, TransformPolicy.NONE,
                LifecyclePolicy.PUBLISHED, Map.of()));
        return Map.of("status", "registered", "id", id);
    }

    /**
     * Update an existing route in place — {@code POST gatewayroutes/{id}} with any of {@code path},
     * {@code serviceId}, {@code order}, {@code lifecycle} (PUBLISHED | DEPRECATED | RETIRED),
     * {@code sunset}. Everything not provided is PRESERVED (auth, traffic, transform, metadata — the
     * policies a delete-and-recreate would lose). Re-registering refreshes the edge, so a lifecycle
     * flip to RETIRED pauses traffic (410 Gone) immediately and back to PUBLISHED resumes it.
     */
    @WriteOperation
    public Map<String, Object> updateRoute(@Selector String id, @Nullable String path,
            @Nullable String serviceId, @Nullable Integer order, @Nullable String lifecycle,
            @Nullable String sunset) {
        RouteDefinition existing = routeSource.routes().stream()
                .filter(route -> route.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown route '" + id + "'"));
        if (serviceId != null && services.find(serviceId).isEmpty()) {
            throw new IllegalArgumentException("Unknown service '" + serviceId + "'");
        }
        List<RoutePredicate> predicates = path == null ? existing.predicates()
                : List.of(new RoutePredicate(RoutePredicate.Kind.PATH, List.of(path)));
        LifecyclePolicy lifecyclePolicy = existing.lifecycle();
        if (lifecycle != null) {
            RouteLifecycle status;
            try {
                status = RouteLifecycle.valueOf(lifecycle.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "lifecycle must be one of PUBLISHED, DEPRECATED, RETIRED");
            }
            lifecyclePolicy = new LifecyclePolicy(status, sunset != null ? sunset : existing.lifecycle().sunset());
        }
        RouteDefinition updated = new RouteDefinition(id,
                order != null ? order : existing.order(),
                predicates,
                serviceId != null ? serviceId : existing.serviceId(),
                existing.auth(), existing.traffic(), existing.transform(),
                lifecyclePolicy, existing.metadata());
        registrar.register(updated);
        return Map.of("status", "updated", "id", id,
                "lifecycle", lifecyclePolicy.status().name());
    }

    @DeleteOperation
    public void deleteRoute(@Selector String id) {
        registrar.remove(id);
    }

    private static String firstPath(RouteDefinition route) {
        return route.predicates().stream()
                .filter(predicate -> predicate.kind() == RoutePredicate.Kind.PATH)
                .flatMap(predicate -> predicate.args().stream())
                .findFirst().orElse("");
    }
}
