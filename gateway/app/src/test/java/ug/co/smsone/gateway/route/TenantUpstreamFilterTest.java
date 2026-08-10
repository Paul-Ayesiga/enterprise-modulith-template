package ug.co.smsone.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistry;
import ug.co.smsone.gateway.core.security.EdgeOrganization;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * The part of organization-routing that {@code TenantUpstreamTest} cannot reach by driving HTTP: what
 * the filter does with a FAILURE coming back from a tenant's own deployment, and what it leaves alone.
 *
 * <p>ADR 0011 §2.2's rule is that "unreachable" is a connection answer and never a data answer, and the
 * whole value of the 503 depends on holding that line. A connection the tenant deployment refused is
 * "this organization cannot be served right now, retry" — bounded and honest. A timeout is not: the
 * request WAS accepted, and dressing that up as a 503 with a {@code Retry-After} both erases the 504
 * the shared path has always produced and turns a bug into a client that politely retries forever.
 */
class TenantUpstreamFilterTest {

    private static final ServiceDefinition TENANT = new ServiceDefinition("tenant-backend",
            List.of(URI.create("http://tenant.internal:8080")), "/health");
    private static final Route SHARED_ROUTE = Route.async().id("secured").order(11)
            .uri(URI.create("http://shared:8080"))
            .predicate(exchange -> true)
            .metadata(Map.of("response-timeout", 15000L))
            .build();

    @Test
    void aRetargetedRequestKeepsItsRouteIdOrderAndMetadataAndOnlyMovesTheUri() {
        MockServerWebExchange exchange = placed("tenant-alias");

        assertThat(filter().filter(exchange, ok()).block()).isNull();

        Route routed = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        assertThat(routed.getUri()).as("the tenant's own deployment").hasToString("http://tenant.internal:8080");
        assertThat(routed.getId()).as("the access log, metrics and usage meter key on this").isEqualTo("secured");
        assertThat(routed.getOrder()).isEqualTo(11);
        assertThat(routed.getMetadata()).as("the per-route response timeout must survive the swap")
                .containsEntry("response-timeout", 15000L);
        assertThat(exchange.getResponse().getHeaders().getFirst(TenantUpstreamFilter.UPSTREAM_HEADER))
                .isEqualTo("tenant-backend");
    }

    @Test
    void anOrganizationWithNoDedicatedDeploymentLeavesTheRouteAlone() {
        MockServerWebExchange exchange = placed("globex");

        assertThat(filter().filter(exchange, ok()).block()).isNull();

        assertThat((Route) exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR))
                .as("untouched — which is every tenant today").isSameAs(SHARED_ROUTE);
        assertThat(exchange.getResponse().getHeaders().getFirst(TenantUpstreamFilter.UPSTREAM_HEADER)).isNull();
    }

    @Test
    void aConnectionTheTenantDeploymentRefusedIs503WithRetryAfter() {
        MockServerWebExchange exchange = placed("tenant-alias");
        // Wrapped, because that is how it actually arrives: reactor-netty raises its own annotated
        // ConnectException and the routing filter may wrap it again — which is why the filter WALKS
        // the cause chain instead of catching a type.
        GatewayFilterChain refused = ex -> Mono.error(
                new IllegalStateException("routing", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> filter().filter(exchange, refused).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(thrown -> assertThat(((ResponseStatusException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("7");
    }

    @Test
    void aSlowTenantDeploymentStaysATimeoutAndIsNotDressedUpAs503() {
        MockServerWebExchange exchange = placed("tenant-alias");
        TimeoutException timeout = new TimeoutException("did not observe any item within 15000ms");

        // Exceptions.unwrap because block() wraps a CHECKED exception in a ReactiveException; the
        // filter itself passes the original through untouched, which is what is being asserted.
        assertThatThrownBy(() -> filter().filter(exchange, ex -> Mono.error(timeout)).block())
                .as("a 504 must not become a 503 with a Retry-After — the request WAS accepted")
                .satisfies(thrown -> assertThat(Exceptions.unwrap(thrown)).isSameAs(timeout));
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void aFaultThatIsNotAConnectionFailurePropagatesUnchanged() {
        MockServerWebExchange exchange = placed("tenant-alias");
        IllegalStateException fault = new IllegalStateException("something else entirely");

        assertThatThrownBy(() -> filter().filter(exchange, ex -> Mono.error(fault)).block())
                .as("serving a bug as 'temporarily unavailable' is how it stays unfixed")
                .isSameAs(fault);
    }

    /** No route matched at all: the 404 is the router's answer, and placement has nothing to say. */
    @Test
    void aRequestThatMatchedNoRouteIsLeftToTheRouter() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/nowhere").build());
        GatewayAttributes.putOrganization(exchange, new EdgeOrganization("tenant-alias", null));

        assertThat(filter().filter(exchange, ok()).block()).isNull();
        assertThat(exchange.getAttributes().get(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)).isNull();
    }

    private static MockServerWebExchange placed(String alias) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/secured/x").build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, SHARED_ROUTE);
        GatewayAttributes.putOrganization(exchange, new EdgeOrganization(alias, null));
        return exchange;
    }

    private static GatewayFilterChain ok() {
        return exchange -> Mono.empty();
    }

    private static TenantUpstreamFilter filter() {
        TenantUpstreamProperties properties = new TenantUpstreamProperties(
                Map.of("tenant-alias", "tenant-backend"), Duration.ofSeconds(7));
        ServiceRegistry registry = new ServiceRegistry() {
            @Override
            public Optional<ServiceDefinition> find(String serviceId) {
                return TENANT.id().equals(serviceId) ? Optional.of(TENANT) : Optional.empty();
            }

            @Override
            public List<ServiceDefinition> all() {
                return List.of(TENANT);
            }
        };
        return new TenantUpstreamFilter(new TenantUpstreams(properties, registry), properties, noMeterRegistry());
    }

    private static ObjectProvider<MeterRegistry> noMeterRegistry() {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public MeterRegistry getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return null;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return null;
            }
        };
    }
}
