package ug.co.smsone.gateway.admin;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;
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
        return routeSource.routes().stream().map(route -> Map.<String, Object>of(
                "id", route.id(),
                "order", route.order(),
                "serviceId", route.serviceId(),
                "authenticated", route.auth().requiresToken(),
                "predicates", route.predicates().stream()
                        .map(predicate -> predicate.kind() + " " + predicate.args()).toList())).toList();
    }

    /** Create (or replace) an open route matching a path to a known service; takes effect immediately. */
    @WriteOperation
    public Map<String, Object> createRoute(String id, String path, String serviceId) {
        if (services.find(serviceId).isEmpty()) {
            throw new IllegalArgumentException("Unknown service '" + serviceId + "'");
        }
        registrar.register(new RouteDefinition(id, 1000,
                List.of(new RoutePredicate(RoutePredicate.Kind.PATH, List.of(path))),
                serviceId, AuthPolicy.OPEN, TrafficPolicy.NONE, TransformPolicy.NONE, Map.of()));
        return Map.of("status", "registered", "id", id);
    }

    @DeleteOperation
    public void deleteRoute(@Selector String id) {
        registrar.remove(id);
    }
}
