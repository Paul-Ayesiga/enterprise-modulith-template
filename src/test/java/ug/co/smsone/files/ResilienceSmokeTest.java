package ug.co.smsone.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 3 gate: Resilience4j autoconfiguration loads on Boot 4.1 (the -spring-boot4 module) and
 * the storage circuit breaker OPENS under fault injection (endpoint nobody listens on).
 */
@TestPropertySource(properties = {
        "app.storage.endpoint=http://localhost:59999",
        "resilience4j.circuitbreaker.instances.storage.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.storage.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.storage.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.storage.wait-duration-in-open-state=60s",
})
class ResilienceSmokeTest extends AbstractIntegrationTest {

    @Autowired
    private FileStorageProvider storage;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    void circuitBreakerOpensUnderFaultInjection() {
        // 4 failing calls against a dead endpoint fill the sliding window
        for (int i = 0; i < 4; i++) {
            assertThat(catchThrowable(() -> storage.exists("fault-probe")))
                    .as("call %d must fail against the dead endpoint", i + 1)
                    .isNotNull()
                    .isNotInstanceOf(CallNotPermittedException.class);
        }

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("storage");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // breaker now short-circuits without touching the endpoint
        assertThatThrownBy(() -> storage.exists("fault-probe"))
                .isInstanceOf(CallNotPermittedException.class);
    }
}
