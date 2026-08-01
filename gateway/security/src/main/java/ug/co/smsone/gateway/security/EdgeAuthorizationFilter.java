package ug.co.smsone.gateway.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.core.security.EdgePrincipal;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * Coarse edge authorization (ADR 0007 §8): after the route is matched and the token validated, apply
 * that route's {@link AuthPolicy}. Open routes pass. Otherwise a token is required (else 401); every
 * required scope must be present (else 403); and, when the route is tenant-scoped, the tenant in the
 * path must equal the token's tenant (else 403). On success the principal + tenant are stamped for
 * downstream (`X-Auth-Subject`, `X-Tenant-Id`) and the bearer is forwarded — services keep their own
 * fine-grained checks. Runs early, before the routing/proxy filter, so a denial never reaches a backend.
 */
@Component
public class EdgeAuthorizationFilter implements GlobalFilter, Ordered {

    private final Map<String, CompiledPolicy> policies;
    private final String tenantClaim;

    public EdgeAuthorizationFilter(RouteSource routeSource, SecurityProperties properties) {
        this.tenantClaim = properties.tenantClaim();
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
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .flatMap(token -> authorize(exchange, chain, compiled, token.getToken()))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")));
    }

    private Mono<Void> authorize(ServerWebExchange exchange, GatewayFilterChain chain,
            CompiledPolicy compiled, Jwt jwt) {
        Set<String> scopes = scopesOf(jwt);
        for (String required : compiled.policy().requiredScopes()) {
            if (!scopes.contains(required)) {
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing scope: " + required));
            }
        }
        String tenant = jwt.getClaimAsString(tenantClaim);
        if (compiled.tenantPattern() != null) {
            String pathTenant = extractTenant(compiled.tenantPattern(), exchange);
            if (pathTenant != null && !pathTenant.equals(tenant)) {
                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch"));
            }
        }
        EdgePrincipal principal = new EdgePrincipal(jwt.getSubject(), tenant, scopes);
        GatewayAttributes.putPrincipal(exchange, principal);
        return chain.filter(stamp(exchange, principal));
    }

    private CompiledPolicy policyFor(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? null : policies.get(route.getId());
    }

    private static ServerWebExchange stamp(ServerWebExchange exchange, EdgePrincipal principal) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set("X-Auth-Subject", principal.subject());
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
