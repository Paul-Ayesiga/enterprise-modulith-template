package ug.co.smsone.gateway.route;

import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.BooleanSpec;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.PredicateSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.route.builder.UriSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import ug.co.smsone.gateway.config.GatewayProperties;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteSource;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistry;
import ug.co.smsone.gateway.core.traffic.TrafficPolicy;
import ug.co.smsone.gateway.core.transform.TransformPolicy;

/**
 * The adapter from the core's route MODEL to the Spring Cloud Gateway RUNTIME: it translates each
 * {@link RouteDefinition} into an SCG route, mapping our predicate kinds onto SCG's predicate
 * factories. This is the only place the core's model meets the runtime — the core never sees SCG.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
class GatewayRouteLocator {

    @Bean
    RouteLocator gatewayRoutes(RouteLocatorBuilder builder, RouteSource routeSource, ServiceRegistry services,
            RedisRateLimiter rateLimiter, KeyResolver keyResolver) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (RouteDefinition route : routeSource.routes()) {
            if (route.predicates().isEmpty()) {
                throw new IllegalStateException("Route '" + route.id() + "' has no predicates");
            }
            ServiceDefinition service = services.find(route.serviceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Route '" + route.id() + "' targets unknown service '" + route.serviceId() + "'"));
            TrafficPolicy traffic = route.traffic();
            routes.route(route.id(), spec -> {
                UriSpec withFilters = match(spec, route.predicates())
                        .filters(filters -> applyTraffic(
                                applyTransform(filters, route.transform()), traffic, route.id(), rateLimiter, keyResolver));
                if (traffic.hasTimeout()) {
                    // Per-route response timeout — a slow backend fails fast (504) rather than hanging.
                    withFilters = withFilters.metadata("response-timeout", traffic.responseTimeoutMs());
                }
                // A multi-instance service is addressed as lb://id so Spring Cloud LoadBalancer picks an
                // instance per request; a single-instance service goes straight to its uri.
                return withFilters.uri(service.loadBalanced() ? URI.create("lb://" + service.id()) : service.uri());
            });
        }
        return routes.build();
    }

    private static UriSpec applyTraffic(GatewayFilterSpec filters, TrafficPolicy traffic, String routeId,
            RedisRateLimiter rateLimiter, KeyResolver keyResolver) {
        GatewayFilterSpec spec = filters;
        if (traffic.hasMaxRequestBytes()) {
            spec = spec.setRequestSize(DataSize.ofBytes(traffic.maxRequestBytes()));
        }
        if (traffic.rateLimited()) {
            spec = spec.requestRateLimiter(config -> config.setRateLimiter(rateLimiter).setKeyResolver(keyResolver));
        }
        if (traffic.retries() > 0) {
            // Retry only idempotent GETs on a server error (ADR 0005's spirit — never replay a write).
            spec = spec.retry(config -> config.setRetries(traffic.retries())
                    .setMethods(HttpMethod.GET)
                    .setSeries(HttpStatus.Series.SERVER_ERROR));
        }
        if (traffic.circuitBreaker()) {
            // A backend 5xx counts as a failure (not just an exception/timeout), tripping the circuit.
            spec = spec.circuitBreaker(config -> config.setName("cb-" + routeId)
                    .setFallbackUri("forward:/__fallback")
                    .setStatusCodes(java.util.Set.of("500", "502", "503", "504")));
        }
        if (traffic.hasCache()) {
            // Cache GET responses for the TTL; the key is tenant-scoped (TenantAwareCacheKeyGenerator).
            spec = spec.localResponseCache(Duration.ofSeconds(traffic.cacheTtlSeconds()), DataSize.ofMegabytes(10));
        }
        return spec;
    }

    /** Apply the route's request/response transforms — path/header/query rewrites, entirely by config. */
    private static GatewayFilterSpec applyTransform(GatewayFilterSpec filters, TransformPolicy transform) {
        GatewayFilterSpec spec = filters;
        if (transform.rewritesPath()) {
            spec = spec.rewritePath(transform.rewritePathRegex(), transform.rewritePathReplacement());
        }
        if (transform.stripsPrefix()) {
            spec = spec.stripPrefix(transform.stripPrefix());
        }
        for (var header : transform.setRequestHeaders().entrySet()) {
            // set (overwrite), not add — a client cannot spoof an injected X-Tenant/X-Consumer.
            spec = spec.setRequestHeader(header.getKey(), header.getValue());
        }
        for (String header : transform.removeRequestHeaders()) {
            spec = spec.removeRequestHeader(header);
        }
        for (var param : transform.addRequestParams().entrySet()) {
            spec = spec.addRequestParameter(param.getKey(), param.getValue());
        }
        for (var header : transform.setResponseHeaders().entrySet()) {
            spec = spec.setResponseHeader(header.getKey(), header.getValue());
        }
        for (String header : transform.removeResponseHeaders()) {
            spec = spec.removeResponseHeader(header);
        }
        return spec;
    }

    /** Fold our predicate list onto SCG's fluent spec, AND-combining each. */
    private static BooleanSpec match(PredicateSpec spec, List<RoutePredicate> predicates) {
        Iterator<RoutePredicate> iterator = predicates.iterator();
        BooleanSpec combined = apply(spec, iterator.next());
        while (iterator.hasNext()) {
            combined = apply(combined.and(), iterator.next());
        }
        return combined;
    }

    private static BooleanSpec apply(PredicateSpec spec, RoutePredicate predicate) {
        List<String> args = predicate.args();
        return switch (predicate.kind()) {
            case PATH -> spec.path(args.toArray(String[]::new));
            case HOST -> spec.host(args.toArray(String[]::new));
            case METHOD -> spec.method(args.toArray(String[]::new));
            case HEADER -> args.size() > 1 ? spec.header(args.get(0), args.get(1)) : spec.header(args.get(0));
            case QUERY -> args.size() > 1 ? spec.query(args.get(0), args.get(1)) : spec.query(args.get(0));
        };
    }
}
