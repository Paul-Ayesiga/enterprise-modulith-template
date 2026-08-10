package ug.co.smsone.gateway.route;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.security.EdgeOrganization;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * Routes by the caller's ORGANIZATION, not only by path — ADR 0010 §7 Phase 8, the edge half of "own
 * deployment". A tenant that has been moved onto a deployment of its own is sent there; everybody else
 * is sent exactly where they were sent before this class existed.
 *
 * <h2>How it re-targets, and why by swapping the route</h2>
 *
 * <p>A path predicate has already chosen a route, and that route names the shared modulith. This filter
 * replaces {@code GATEWAY_ROUTE_ATTR} with the SAME route — same id, order, predicate, filters and
 * metadata — pointing at the tenant's service, and runs before {@code RouteToRequestUrlFilter}
 * ({@code order 10000}) derives the target URL. Everything downstream then behaves as though the route
 * had always pointed there: the load balancer resolves a multi-instance tenant deployment, the
 * per-route response timeout in the metadata still applies, and the access log, metrics and usage meter
 * still see the route id they were configured against. Rewriting {@code GATEWAY_REQUEST_URL_ATTR}
 * instead would have skipped the load balancer and quietly pinned a multi-instance tenant to one host.
 *
 * <h2>Three rules, in the order they are decided</h2>
 *
 * <p><b>1. No organization, no change.</b> The organization is only ever populated for a route the edge
 * AUTHENTICATED (see {@code GatewayAttributes.organization}), so {@code /api/v1/signup}, the Pesapal
 * IPN/callback and the Kill Bill notification — callers who by nature hold no token — reach this filter
 * with {@link EdgeOrganization#NONE} and pass straight through. That is structural, not an exclusion
 * list: those surfaces are platform-tier anyway (a prospect has no tenant yet; plans and subscriptions
 * live on the platform), so a tenant deployment is the wrong place for them even if one existed. A
 * trusted {@code X-Internal-Token} service lands here too — it holds no organization by construction
 * and already bypasses tenant enforcement — so a platform-side service keeps reaching the shared
 * deployment. Giving one reach into a tenant's own deployment is a decision nobody has made yet, and
 * making it silently by inference from a shared secret is not how it should get made.
 *
 * <p><b>2. No entry, shared deployment.</b> Which is every tenant today, and must stay a no-op.
 *
 * <p><b>3. Otherwise the tenant's own deployment — or a 503, never a fallback.</b> Falling back to the
 * shared modulith for a tenant whose rows have moved would answer from a database that no longer holds
 * them: an empty member list and a 404 on a ticket that exists, with a 200 on the front. ADR 0011 §2.4
 * settles the trade — a misroute is the worst failure available, a refusal is bounded — so the two ways
 * this can go wrong both refuse. A configured upstream that no {@code gateway.services} entry registers
 * is refused by {@link TenantUpstreams} before the request leaves; an upstream that cannot be REACHED is
 * refused here, on the way back.
 *
 * <h2>Unreachable is a connection answer, never a data answer</h2>
 *
 * <p>Only connection-shaped failures — connect refused, unresolvable host — become the 503. A timeout
 * stays a 504 and a 5xx from the tenant's own deployment is its own answer, passed through untouched.
 * That line is ADR 0011 §2.2's, and it is drawn for the same reason: dressing a bug up as
 * "temporarily unavailable with a Retry-After" turns a loud failure into a client that politely retries
 * forever. The mapping applies ONLY to a request this filter re-targeted, so the shared upstream's
 * 502/504 behaviour is byte-for-byte what it was.
 *
 * <h2>Why the retarget is also announced on the exchange</h2>
 *
 * <p>{@link #UPSTREAM_ATTR} is what {@link TenantAwareCircuitBreaker} reads, and without it the whole
 * refusal contract above is bypassed on any route carrying {@code circuit-breaker: true}. That filter
 * is route-scoped (order 0) and therefore runs INSIDE the {@code chain.filter(exchange)} below, with a
 * fallback URI always configured — so it catches the connect failure, forwards to {@code /__fallback}
 * and completes NORMALLY. {@code onErrorResume} never fires, the caller gets a bare 503 with no
 * {@code Retry-After}, and nothing logs or counts which organization was refused.
 */
@Component
class TenantUpstreamFilter implements GlobalFilter, Ordered {

    /** The tenant deployment that served the request — the dedicated twin of {@code X-Gateway-Upstream}. */
    static final String UPSTREAM_HEADER = "X-Gateway-Tenant-Upstream";

    /**
     * Present, holding the dedicated service id, exactly when this request has been re-targeted. Read by
     * {@link TenantAwareCircuitBreaker}, and never set for the shared deployment — which is what keeps
     * every route byte-for-byte unchanged while nobody has their own deployment.
     */
    static final String UPSTREAM_ATTR = "gw.tenantUpstream";

    private static final Logger errorLog = LoggerFactory.getLogger("gateway.error");

    /**
     * The caller's remedy is identical whichever way the placement failed, so the wire says one thing.
     * Which organization is misconfigured, and which config key repairs it, is operator information and
     * stays in the log line (AGENTS §9). Deliberately the same sentence the platform's own
     * {@code TenantSchemaFloor} refusal makes, because it is the same claim.
     */
    private static final String NOT_SERVICEABLE =
            "This organization is served by a deployment that cannot be reached right now. "
                    + "Nothing is lost and no action is needed — retry after the interval in the "
                    + "Retry-After header.";

    private final TenantUpstreams upstreams;
    private final TenantUpstreamProperties properties;
    private final MeterRegistry meterRegistry;

    TenantUpstreamFilter(TenantUpstreams upstreams, TenantUpstreamProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.upstreams = upstreams;
        this.properties = properties;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange); // no route matched — the 404 is somebody else's answer
        }
        EdgeOrganization organization = GatewayAttributes.organization(exchange);
        TenantUpstreams.Decision decision = upstreams.decide(organization);
        if (decision.isRefused()) {
            return refuse(exchange, decision.organizationName(), decision.refusalCode(),
                    "Organization '" + decision.organizationName() + "' " + decision.reason());
        }
        if (!decision.isDedicated()) {
            return chain.filter(exchange);
        }
        ServiceDefinition service = decision.service();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, retarget(route, service));
        exchange.getAttributes().put(UPSTREAM_ATTR, service.id());
        exchange.getResponse().getHeaders().set(UPSTREAM_HEADER, service.id());
        count("gateway.tenant.routed", "upstream", service.id());
        String organizationName = organization.alias() != null ? organization.alias() : organization.id();
        return chain.filter(exchange)
                .onErrorResume(failure -> couldNotBeReached(failure)
                        ? refuse(exchange, organizationName,
                                TenantUpstreams.Decision.UPSTREAM_UNREACHABLE,
                                "Organization '" + organizationName + "' is served by '" + service.id()
                                        + "', which did not accept the connection: " + failure)
                        : Mono.error(failure));
    }

    /**
     * The same route, pointed somewhere else. {@code replaceFilters} carries the already-built
     * route-scoped filters and {@code metadata} carries the response timeout; neither is re-derived,
     * because the filter chain was fixed by {@code FilteringWebHandler} before any global filter ran and
     * a route that disagreed with the chain actually executing would be a trap for the next reader.
     */
    private static Route retarget(Route route, ServiceDefinition service) {
        return Route.async()
                .id(route.getId())
                .order(route.getOrder())
                .uri(ServiceAddress.of(service))
                .asyncPredicate(route.getPredicate())
                .replaceFilters(route.getFilters())
                .metadata(route.getMetadata())
                .build();
    }

    /**
     * 503 + {@code Retry-After}, loud in the operator's log and counted, and never a fallback. Written
     * as a {@link ResponseStatusException} so {@code GatewayExceptionHandler} renders it in the one
     * envelope every other edge failure uses, with the request id attached.
     */
    private Mono<Void> refuse(ServerWebExchange exchange, String organization, String refusalCode,
            String operatorDetail) {
        errorLog.error("tenant_upstream_refused reason={} organization={} method={} path={} rid={} — {}",
                refusalCode, organization, exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getRawPath(), GatewayAttributes.requestId(exchange),
                operatorDetail);
        count("gateway.tenant.refusals", "reason", refusalCode);
        if (!exchange.getResponse().isCommitted()) {
            // Committed means the tenant's deployment already began streaming; the handler propagates
            // rather than corrupting a response in flight, and a header set here would throw.
            exchange.getResponse().getHeaders()
                    .set(HttpHeaders.RETRY_AFTER, String.valueOf(properties.retryAfterSeconds()));
        }
        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_SERVICEABLE));
    }

    /**
     * Did the upstream fail to ACCEPT a connection, as opposed to answering slowly or answering badly?
     *
     * <p>Walked rather than caught by type, for the same reason {@code CurrentUserFilter} walks its
     * cause chain: reactor-netty wraps the real {@link ConnectException} in its own annotated subclass
     * and the routing filter may wrap that again, so a {@code catch} of the interesting type compiles
     * and never fires. Guarded against a self-referential chain, which would otherwise hang the request
     * thread inside a filter.
     *
     * <p>{@link NotFoundException} is on the list because of one narrow window it is the only symptom
     * of: a multi-instance tenant upstream addressed as {@code lb://} whose service was deregistered
     * between this filter's snapshot and the load balancer's own lookup. That is the same fact — the
     * tenant's deployment could not be reached — arriving one stage later, and without this it would
     * answer 503 with no {@code Retry-After} and no line naming the organization.
     */
    private static boolean couldNotBeReached(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof UnresolvedAddressException || cause instanceof NotFoundException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    private void count(String name, String tag, String value) {
        if (meterRegistry != null) {
            meterRegistry.counter(name, tag, value).increment();
        }
    }

    @Override
    public int getOrder() {
        // After auth (+10, which resolves the organization), quota (+15) and plugins (+20): the last
        // decision the edge makes before RouteToRequestUrlFilter (10000) turns the route into a URL.
        return Ordered.HIGHEST_PRECEDENCE + 25;
    }
}
