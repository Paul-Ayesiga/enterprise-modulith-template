package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RouteSource;
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.security.EdgeAuthorizationFilter;
import ug.co.smsone.gateway.security.SecurityProperties;

/**
 * Regression for the edge authorization filter. A downstream filter that completes the response
 * without emitting — the rate limiter answering 429 via {@code setComplete()} — must NOT make the
 * filter forge a 401. The original code placed {@code switchIfEmpty} after the authorize flatMap, so
 * that empty completion tripped a spurious 401 which collided with the already-committed 429 and
 * surfaced to the client as a 500 under load. The filter must instead pass the empty completion through.
 *
 * <p>Uses the internal-token principal path so the test needs no JWKS/JWT plumbing; the switchIfEmpty
 * placement it exercises is identical for every credential kind.
 */
class EdgeAuthorizationFilterTest {

    private static final String INTERNAL_TOKEN = "internal-secret";
    private static final Route AUTHENTICATED_ROUTE =
            Route.async().id("r1").uri(URI.create("http://backend")).predicate(exchange -> true).build();

    private static EdgeAuthorizationFilter filter() {
        RouteDefinition route = new RouteDefinition("r1", 0, null, "svc",
                new AuthPolicy(true, Set.of(), null), null, null, null, null);
        RouteSource routeSource = () -> List.of(route);
        SecurityProperties properties = new SecurityProperties(null, null, "tenant", "organization", null,
                List.of(new SecurityProperties.InternalToken("svc", INTERNAL_TOKEN, Set.of("api"))));
        return new EdgeAuthorizationFilter(routeSource, properties, none(), none(), none());
    }

    @Test
    void downstreamShortCircuitDoesNotForgeA401() {
        ServerWebExchange exchange = withRoute(MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("X-Internal-Token", INTERNAL_TOKEN).build()));

        // A chain that completes empty — exactly what the rate limiter's setComplete() does on a 429.
        GatewayFilterChain shortCircuit = ex -> Mono.empty();

        // Must complete cleanly, forging no 401. (Before the fix this errored 401 → a 500 to the client.)
        assertThat(filter().filter(exchange, shortCircuit).block()).isNull();
    }

    @Test
    void missingPrincipalStillDenies401() {
        ServerWebExchange exchange = withRoute(MockServerWebExchange.from(MockServerHttpRequest.get("/x").build()));

        assertThatThrownBy(() -> filter().filter(exchange, ex -> Mono.empty()).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static MockServerWebExchange withRoute(MockServerWebExchange exchange) {
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, AUTHENTICATED_ROUTE);
        return exchange;
    }

    private static <T> ObjectProvider<T> none() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }
}
