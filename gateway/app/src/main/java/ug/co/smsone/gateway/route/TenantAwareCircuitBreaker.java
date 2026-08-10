package ug.co.smsone.gateway.route;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * A route's circuit breaker, applied only to the service that route was configured against — ADR 0010
 * §7 Phase 8. A request {@link TenantUpstreamFilter} has re-targeted onto a tenant's own deployment is
 * NOT that service, so the route's breaker does not measure it and does not answer for it.
 *
 * <h2>The two failures this exists to prevent, both of them silent</h2>
 *
 * <p><b>One dead tenant deployment must not open the circuit for everybody else.</b> Spring Cloud names
 * a route-scoped breaker per ROUTE ({@code cb-<routeId>}, see {@link GatewayRouteLocator}), and the
 * retarget deliberately preserves the route id so the access log, metrics and usage meter keep the key
 * they were configured against. Those two facts together mean acme's connect failures would accumulate
 * on the very Resilience4j instance every OTHER tenant's request to that path uses: once it opens, the
 * whole shared modulith answers 503 behind a fallback. That is "refuses per pod" — the outcome
 * {@link TenantUpstreams}' class note cites ADR 0011 §4.2 to forbid, arriving one layer up.
 *
 * <p><b>And the refusal contract must not be swallowed.</b> The circuit-breaker filter is route-scoped
 * (order 0) and therefore runs inside the global {@link TenantUpstreamFilter} (order
 * {@code HIGHEST_PRECEDENCE + 25}); with a fallback URI configured — {@link GatewayRouteLocator} always
 * sets one — it catches the connect failure, forwards to {@code /__fallback} and completes NORMALLY.
 * The filter's {@code onErrorResume} then never fires: no {@code Retry-After}, no
 * {@code tenant_upstream_refused} line naming the organization, no {@code gateway.tenant.refusals}
 * metric. Bypassing the breaker restores all of it, because the failure is once again an error.
 *
 * <h2>Bypass rather than a second breaker, and what that costs</h2>
 *
 * <p>The alternative was a breaker per {@code (routeId, upstream)}. It ends up needing three quiet
 * divergences from the route's declared policy to be correct — a different name, no fallback URI (or
 * the refusal is swallowed again) and no status-code list (or the tenant's own 5xx stops being its own
 * answer, contradicting ADR 0011 §2.2) — and each of those is its own trap for the next reader. The
 * honest version is the rule stated above: a route's traffic policy was written for the route's
 * service. What a tenant deployment gets instead is the per-tenant refusal this phase built, which is
 * bounded and names the tenant, plus the route's {@code response-timeout}, which survives the retarget.
 * What it does NOT get is fail-fast on a deployment that accepts connections and then hangs; that
 * costs one tenant one response-timeout per request, and it costs no other tenant anything.
 *
 * <p>Applied only when at least one organization actually has its own deployment
 * ({@code gateway.tenancy.upstreams} non-empty). While nobody does — which is today — the route is
 * built with the plain circuit-breaker filter and nothing here is in the chain at all.
 */
final class TenantAwareCircuitBreaker implements GatewayFilter {

    private final GatewayFilter routeCircuitBreaker;

    TenantAwareCircuitBreaker(GatewayFilter routeCircuitBreaker) {
        this.routeCircuitBreaker = routeCircuitBreaker;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (exchange.getAttributes().containsKey(TenantUpstreamFilter.UPSTREAM_ATTR)) {
            return chain.filter(exchange);
        }
        return routeCircuitBreaker.filter(exchange, chain);
    }

    /**
     * Delegates, because this string is what {@code /actuator/gatewayroutes} prints for the route's
     * filters — an operator reading it is asking what the route does, not what wraps it.
     */
    @Override
    public String toString() {
        return routeCircuitBreaker.toString();
    }
}
