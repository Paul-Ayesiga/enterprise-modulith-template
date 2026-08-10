package ug.co.smsone.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * A route's circuit breaker belongs to the route's own service, and a request that has been re-targeted
 * onto a tenant's deployment is not it.
 *
 * <p>The stub here stands in for {@code SpringCloudCircuitBreakerFilterFactory}'s filter, and what it
 * mimics is the behaviour that made this class necessary: with a fallback URI configured — and
 * {@link GatewayRouteLocator} always configures one — the real filter CATCHES the downstream failure,
 * forwards to {@code /__fallback} and completes NORMALLY. So a tenant deployment refusing the
 * connection stops being an error before {@link TenantUpstreamFilter}'s {@code onErrorResume} can see
 * it: no {@code Retry-After}, no log line naming the organization, no refusal metric. And because the
 * breaker is named per route while the retarget keeps the route id, those failures would land on the
 * instance every OTHER tenant on that path shares.
 */
class TenantAwareCircuitBreakerTest {

    @Test
    void aRetargetedRequestNeverReachesTheRoutesBreaker() {
        AtomicInteger throughBreaker = new AtomicInteger();
        AtomicInteger reachedUpstream = new AtomicInteger();
        MockServerWebExchange exchange = exchange();
        exchange.getAttributes().put(TenantUpstreamFilter.UPSTREAM_ATTR, "modulith-acme");

        StepResult result = run(exchange, throughBreaker, reachedUpstream);

        assertThat(throughBreaker.get())
                .as("one dead tenant deployment must not accumulate failures on the shared route's circuit")
                .isZero();
        assertThat(reachedUpstream.get()).as("the request still goes to the tenant's deployment").isEqualTo(1);
        assertThat(result.failure())
                .as("the connect failure stays an ERROR, which is the only thing the refusal contract "
                        + "in TenantUpstreamFilter can act on")
                .isNotNull()
                .hasMessage("Connection refused");
    }

    @Test
    void everybodyElseStillGoesThroughItAndStillGetsTheFallback() {
        AtomicInteger throughBreaker = new AtomicInteger();
        AtomicInteger reachedUpstream = new AtomicInteger();

        StepResult result = run(exchange(), throughBreaker, reachedUpstream);

        assertThat(throughBreaker.get()).as("which is every request today").isEqualTo(1);
        assertThat(reachedUpstream.get()).isEqualTo(1);
        assertThat(result.failure())
                .as("the shared route's breaker swallows the failure into its fallback, exactly as before")
                .isNull();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/cbtenant/x").build());
    }

    /**
     * Runs the wrapper over a breaker stub that behaves like the real one — it swallows whatever the
     * chain raises and completes — with a chain that always refuses the connection.
     */
    private static StepResult run(MockServerWebExchange exchange, AtomicInteger throughBreaker,
            AtomicInteger reachedUpstream) {
        GatewayFilter breaker = (ex, chain) -> {
            throughBreaker.incrementAndGet();
            return chain.filter(ex).onErrorResume(failure -> Mono.empty()); // forward:/__fallback, in effect
        };
        GatewayFilterChain upstream = ex -> {
            reachedUpstream.incrementAndGet();
            return Mono.error(new java.net.ConnectException("Connection refused"));
        };
        try {
            new TenantAwareCircuitBreaker(breaker).filter(exchange, upstream).block();
            return new StepResult(null);
        } catch (Throwable failure) {
            return new StepResult(reactor.core.Exceptions.unwrap(failure));
        }
    }

    private record StepResult(Throwable failure) {
    }
}
