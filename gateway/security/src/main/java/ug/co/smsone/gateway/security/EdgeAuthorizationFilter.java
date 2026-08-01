package ug.co.smsone.gateway.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RouteSource;
import ug.co.smsone.gateway.core.security.ApiKeyIntrospector;
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.core.security.EdgePrincipal;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * Coarse edge authorization (ADR 0007 §8): after the route is matched, resolve the caller to an
 * {@link EdgePrincipal} — from a validated bearer JWT, or from an {@code X-Api-Key} via the platform
 * {@link ApiKeyIntrospector} when one is present — and apply that route's {@link AuthPolicy}. Open
 * routes pass. Otherwise a principal is required (else 401); every required scope must be present
 * (else 403); and a tenant-scoped route's path tenant must equal the principal's (else 403). On
 * success the subject + tenant are stamped downstream ({@code X-Auth-Subject}, {@code X-Tenant-Id})
 * and the credential is forwarded — services keep their own fine-grained checks. Runs before the
 * routing/proxy filter, so a denial never reaches a backend.
 */
@Component
public class EdgeAuthorizationFilter implements GlobalFilter, Ordered {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final Map<String, CompiledPolicy> policies;
    private final String tenantClaim;
    private final ApiKeyIntrospector introspector;

    public EdgeAuthorizationFilter(RouteSource routeSource, SecurityProperties properties,
            ObjectProvider<ApiKeyIntrospector> introspector) {
        this.tenantClaim = properties.tenantClaim();
        this.introspector = introspector.getIfAvailable();
        this.policies = routeSource.routes().stream()
                .collect(Collectors.toUnmodifiableMap(RouteDefinition::id, route -> compile(route.auth())));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return chain.filter(exchange); // CORS preflight carries no credentials — never gate it
        }
        CompiledPolicy compiled = policyFor(exchange);
        if (compiled == null || !compiled.policy().requiresToken()) {
            return chain.filter(exchange);
        }
        return resolvePrincipal(exchange)
                .flatMap(principal -> authorize(exchange, chain, compiled, principal))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")));
    }

    /** A presented API key (when an introspector is available) takes precedence; otherwise the JWT. */
    private Mono<EdgePrincipal> resolvePrincipal(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank() && introspector != null) {
            return introspector.introspect(apiKey);
        }
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> fromJwt(token.getToken()));
    }

    private Mono<Void> authorize(ServerWebExchange exchange, GatewayFilterChain chain,
            CompiledPolicy compiled, EdgePrincipal principal) {
        for (String required : compiled.policy().requiredScopes()) {
            if (!principal.hasScope(required)) {
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing scope: " + required));
            }
        }
        if (compiled.tenantPattern() != null) {
            String pathTenant = extractTenant(compiled.tenantPattern(), exchange);
            if (pathTenant != null && !pathTenant.equals(principal.tenant())) {
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch"));
            }
        }
        GatewayAttributes.putPrincipal(exchange, principal);
        return chain.filter(stamp(exchange, principal));
    }

    private EdgePrincipal fromJwt(Jwt jwt) {
        return new EdgePrincipal(jwt.getSubject(), jwt.getClaimAsString(tenantClaim), scopesOf(jwt));
    }

    private CompiledPolicy policyFor(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? null : policies.get(route.getId());
    }

    private static ServerWebExchange stamp(ServerWebExchange exchange, EdgePrincipal principal) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    if (principal.subject() != null) {
                        headers.set("X-Auth-Subject", principal.subject());
                    }
                    if (principal.tenant() != null) {
                        headers.set("X-Tenant-Id", principal.tenant());
                    }
                }))
                .build();
    }

    private static String extractTenant(PathPattern pattern, ServerWebExchange exchange) {
        PathContainer path = PathContainer.parsePath(exchange.getRequest().getURI().getRawPath());
        PathPattern.PathMatchInfo info = pattern.matchAndExtract(path);
        return info == null ? null : info.getUriVariables().get("tenant");
    }

    private static Set<String> scopesOf(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            return Set.of(scope.trim().split("\\s+"));
        }
        List<String> scp = jwt.getClaimAsStringList("scp");
        return scp == null ? Set.of() : Set.copyOf(scp);
    }

    private static CompiledPolicy compile(AuthPolicy policy) {
        PathPattern pattern = policy.enforcesTenant()
                ? PathPatternParser.defaultInstance.parse(policy.tenantPathTemplate()) : null;
        return new CompiledPolicy(policy, pattern);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10; // after request-id/access-log, before routing
    }

    private record CompiledPolicy(AuthPolicy policy, PathPattern tenantPattern) {
    }
}
