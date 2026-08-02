package ug.co.smsone.gateway.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteSource;

/**
 * Publishes an OpenAPI 3 document describing the gateway's edge surface — a management {@link Endpoint}
 * (id {@code gatewayopenapi}) on the admin port. Each non-retired route contributes a path (its match
 * pattern), tagged by product, marked {@code deprecated} when its lifecycle says so. It is a map of the
 * routes on offer, not a merge of every backend's own spec — enough for a catalog/portal to render.
 */
@Component
@Endpoint(id = "gatewayopenapi")
public class GatewayOpenApiEndpoint {

    private final RouteSource routeSource;

    public GatewayOpenApiEndpoint(RouteSource routeSource) {
        this.routeSource = routeSource;
    }

    @ReadOperation
    public Map<String, Object> openApi() {
        Map<String, Object> paths = new LinkedHashMap<>();
        for (RouteDefinition route : routeSource.routes()) {
            if (route.lifecycle().isRetired()) {
                continue; // a retired version is not published
            }
            for (String path : GatewayCatalogEndpoint.pathsOf(route)) {
                Map<String, Object> operation = new LinkedHashMap<>();
                operation.put("summary", "Proxies route '" + route.id() + "' to service '" + route.serviceId() + "'");
                operation.put("tags", List.of(route.metadata().getOrDefault("product", "gateway")));
                operation.put("deprecated", route.lifecycle().isDeprecated());
                operation.put("responses", Map.of("200", Map.of("description", "Proxied response")));
                paths.put(path, Map.of(method(route), operation));
            }
        }
        return Map.of(
                "openapi", "3.0.3",
                "info", Map.of("title", "SMSOne Gateway API", "version", "1.0.0"),
                "paths", paths);
    }

    /** The route's method predicate (lower-cased) if it pins one, else GET. */
    private static String method(RouteDefinition route) {
        return route.predicates().stream()
                .filter(predicate -> predicate.kind() == RoutePredicate.Kind.METHOD)
                .flatMap(predicate -> predicate.args().stream())
                .findFirst()
                .map(m -> m.toLowerCase(java.util.Locale.ROOT))
                .orElse("get");
    }
}
