package ug.co.smsone.gateway.config;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ug.co.smsone.gateway.core.route.RoutePredicate;

/**
 * The static route/service catalog as configuration ({@code gateway.*}). This is the config-driven
 * source the {@code RouteSource}/{@code ServiceRegistry} ports read from today; an admin store or a
 * discovery integration can replace it later without the core or the runtime changing.
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(List<ServiceProps> services, List<RouteProps> routes,
        Map<String, PolicyProps> policies) {

    public GatewayProperties {
        services = services == null ? List.of() : services;
        routes = routes == null ? List.of() : routes;
        policies = policies == null ? Map.of() : policies;
    }

    /**
     * A backend: {@code id}, where to health-check it, and its instance(s). Give a single {@code uri}
     * for one instance, or {@code instances} for several (the edge then load-balances across them).
     */
    public record ServiceProps(String id, URI uri, List<URI> instances, String healthPath) {
        public ServiceProps {
            instances = instances == null ? List.of() : instances;
        }

        /** The effective instance list: the explicit {@code instances} if given, else the single {@code uri}. */
        public List<URI> effectiveInstances() {
            if (!instances.isEmpty()) {
                return instances;
            }
            return uri == null ? List.of() : List.of(uri);
        }
    }

    /**
     * A route: {@code predicates}, target {@code serviceId}, and its policy — either a {@code policyRef}
     * to a named {@link PolicyProps}, or inline {@code auth}/{@code traffic}/{@code transform}. Inline
     * config overrides the referenced policy per aspect (route's own {@code auth} wins over the policy's).
     */
    public record RouteProps(String id, int order, String serviceId, List<PredicateProps> predicates,
            AuthProps auth, TrafficProps traffic, TransformProps transform, String policyRef) {
        public RouteProps {
            predicates = predicates == null ? List.of() : predicates;
        }
    }

    /** A named, reusable policy — auth/traffic/transform composed once and attached to routes by {@code policy-ref}. */
    public record PolicyProps(AuthProps auth, TrafficProps traffic, TransformProps transform) {
    }

    /** A route's request/response transformation: path rewrite/strip, header and query manipulation. */
    public record TransformProps(String rewritePathRegex, String rewritePathReplacement, Integer stripPrefix,
            Map<String, String> setRequestHeaders, List<String> removeRequestHeaders,
            Map<String, String> addRequestParams, Map<String, String> setResponseHeaders,
            List<String> removeResponseHeaders) {
    }

    /** A route's coarse edge auth policy: require a token, require scopes, enforce the path tenant. */
    public record AuthProps(boolean authenticated, List<String> scopes, String tenantPathTemplate) {
        public AuthProps {
            scopes = scopes == null ? List.of() : scopes;
        }
    }

    /** A route's traffic policy: timeout, max bytes, rate-limit / circuit-breaker / retries, cache TTL. */
    public record TrafficProps(Long responseTimeoutMs, Long maxRequestBytes, boolean rateLimited,
            boolean circuitBreaker, int retries, Long cacheTtlSeconds) {
    }

    /** A predicate: its {@code kind} (PATH/HOST/HEADER/METHOD/QUERY) and the factory {@code args}. */
    public record PredicateProps(RoutePredicate.Kind kind, List<String> args) {
        public PredicateProps {
            args = args == null ? List.of() : args;
        }
    }
}
