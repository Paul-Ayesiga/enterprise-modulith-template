package ug.co.smsone.gateway.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.config.GatewayProperties;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteSource;
import ug.co.smsone.gateway.core.traffic.TrafficPolicy;

/**
 * The API-product catalog — a management {@link Endpoint} (id {@code gatewaycatalog}) on the admin port,
 * what a developer portal consumes. Routes join a product by their {@code product} label; the catalog
 * lists each product (name/description from {@code gateway.products}) with its routes and their paths,
 * lifecycle, and whether they need a token. Uncategorized routes are grouped on their own.
 */
@Component
@Endpoint(id = "gatewaycatalog")
public class GatewayCatalogEndpoint {

    private static final String NO_PRODUCT = "";

    private final RouteSource routeSource;
    private final GatewayProperties properties;

    public GatewayCatalogEndpoint(RouteSource routeSource, GatewayProperties properties) {
        this.routeSource = routeSource;
        this.properties = properties;
    }

    @ReadOperation
    public List<Map<String, Object>> catalog() {
        Map<String, List<RouteDefinition>> byProduct = routeSource.routes().stream()
                .collect(Collectors.groupingBy(route -> route.metadata().getOrDefault("product", NO_PRODUCT)));

        List<Map<String, Object>> catalog = new ArrayList<>();
        Set<String> productIds = new LinkedHashSet<>(properties.products().keySet());
        productIds.addAll(byProduct.keySet());
        for (String productId : productIds) {
            if (NO_PRODUCT.equals(productId)) {
                continue;
            }
            GatewayProperties.ProductProps product = properties.products().get(productId);
            catalog.add(Map.of(
                    "id", productId,
                    "name", product != null && product.name() != null ? product.name() : productId,
                    "description", product != null && product.description() != null ? product.description() : "",
                    "routes", summaries(byProduct.getOrDefault(productId, List.of()))));
        }
        List<RouteDefinition> uncategorized = byProduct.getOrDefault(NO_PRODUCT, List.of());
        if (!uncategorized.isEmpty()) {
            catalog.add(Map.of("id", "(uncategorized)", "name", "Uncategorized", "description", "",
                    "routes", summaries(uncategorized)));
        }
        return catalog;
    }

    private static List<Map<String, Object>> summaries(List<RouteDefinition> routes) {
        return routes.stream().map(route -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", route.id());
            summary.put("paths", pathsOf(route));
            summary.put("lifecycle", route.lifecycle().status().name());
            if (route.lifecycle().sunset() != null) {
                summary.put("sunset", route.lifecycle().sunset()); // HTTP-date advertised to deprecated callers
            }
            summary.put("authenticated", route.auth().requiresToken());
            summary.put("scopes", List.copyOf(route.auth().requiredScopes()));
            summary.put("traffic", trafficOf(route.traffic()));
            return summary;
        }).toList();
    }

    /** The route's traffic protections, so the portal can show what the edge enforces per route. */
    private static Map<String, Object> trafficOf(TrafficPolicy traffic) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rateLimited", traffic.rateLimited());
        out.put("circuitBreaker", traffic.circuitBreaker());
        if (traffic.hasTimeout()) {
            out.put("responseTimeoutMs", traffic.responseTimeoutMs());
        }
        if (traffic.hasMaxRequestBytes()) {
            out.put("maxRequestBytes", traffic.maxRequestBytes());
        }
        if (traffic.retries() > 0) {
            out.put("retries", traffic.retries());
        }
        if (traffic.hasCache()) {
            out.put("cacheTtlSeconds", traffic.cacheTtlSeconds());
        }
        return out;
    }

    static List<String> pathsOf(RouteDefinition route) {
        return route.predicates().stream()
                .filter(predicate -> predicate.kind() == RoutePredicate.Kind.PATH)
                .flatMap(predicate -> predicate.args().stream())
                .toList();
    }
}
