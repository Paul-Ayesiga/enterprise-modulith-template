package ug.co.smsone.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;
import ug.co.smsone.notification.internal.NotificationDeliveryWorker;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Rate limiting against REAL Valkey (Bucket4j distributed buckets): the shared limiter, the edge
 * filter (429 + envelope + headers, per-tenant isolation), and the notification egress per-channel
 * limit. Enabled here via @TestPropertySource (off by default in the test profile).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        // Generous Valkey timeout so the limiter isn't spuriously fail-opened under container-heavy
        // CI load (production keeps the tight 250ms fail-fast default) — keeps these assertions deterministic.
        "app.rate-limit.backend-timeout=PT5S",
        "app.rate-limit.tenant-claim=tenant",
        "app.rate-limit.tiers[0].id=reads",
        "app.rate-limit.tiers[0].path-pattern=/api/**",
        "app.rate-limit.tiers[0].methods[0]=GET",
        "app.rate-limit.tiers[0].scope=TENANT",
        "app.rate-limit.tiers[0].capacity=3",
        "app.rate-limit.tiers[0].refill-period=PT1M",
        "app.notification.delivery.rate.WEBHOOK.capacity=2",
        "app.notification.delivery.rate.WEBHOOK.period=PT1M"
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DistributedRateLimiter limiter;

    @Autowired
    private Notifications notifications;

    @Autowired
    private NotificationDeliveryWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    /** Summed across tiers so the assert does not care which tier id the test profile configured. */
    private double denied() {
        return meters.find("smsone.ratelimit.denied").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    @Test
    void distributedBucketAllowsUpToCapacityThenDeniesPerKey() {
        String keyA = "rl:test:" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(keyA, 3, Duration.ofMinutes(1), false).allowed()).isTrue();
        }
        RateLimitVerdict denied = limiter.tryConsume(keyA, 3, Duration.ofMinutes(1), false);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterSeconds()).isPositive();

        // an independent key (= a different tenant/principal) has its own bucket
        String keyB = "rl:test:" + UUID.randomUUID();
        assertThat(limiter.tryConsume(keyB, 3, Duration.ofMinutes(1), false).allowed()).isTrue();
    }

    @Test
    void edgeFilterReturns429WithEnvelopeAndHeaders() throws Exception {
        double deniedBefore = denied();
        RequestPostProcessor acme = jwt()
                .jwt(builder -> builder.claim("tenant", "acme-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/settings").with(acme)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/settings").with(acme))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("RateLimit-Policy", Matchers.containsString("reads")))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(jsonPath("$.errors[0].code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.meta.requestId").exists());

        // a different tenant is unaffected — proves per-tenant keying
        RequestPostProcessor other = jwt()
                .jwt(builder -> builder.claim("tenant", "other-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
        mockMvc.perform(get("/api/v1/settings").with(other)).andExpect(status().isOk());

        assertThat(denied()).as("the 429 is counted, not just answered").isEqualTo(deniedBefore + 1);
    }

    /**
     * With no tenant to key on, the bucket falls back to the principal — which must be the immutable
     * subject, not {@code preferred_username}. A name-keyed bucket is escapable (rename yourself for a
     * fresh quota) and inheritable (a recycled username lands in the previous holder's bucket). Built
     * through the real converter, which is what makes principal name differ from subject.
     */
    @Test
    void principalFallbackKeysBySubjectNotUsername() throws Exception {
        var converter = new ug.co.smsone.shared.security.KeycloakJwtAuthenticationConverter();
        String subject = "sub-" + UUID.randomUUID();

        java.util.function.Function<String, RequestPostProcessor> as = username ->
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .authentication(converter.convert(org.springframework.security.oauth2.jwt.Jwt
                                .withTokenValue("token").header("alg", "RS256")
                                .subject(subject)
                                .claim("preferred_username", username)
                                .claim("realm_access", Map.of("roles", List.of("USER")))
                                .issuedAt(java.time.Instant.now())
                                .expiresAt(java.time.Instant.now().plusSeconds(300))
                                .build()));

        // No tenant claim and no org claim -> PRINCIPAL fallback. Capacity is 3 for this tier.
        mockMvc.perform(get("/api/v1/settings").with(as.apply("name-before"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings").with(as.apply("name-before"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings").with(as.apply("name-before"))).andExpect(status().isOk());

        // Same subject, different display name: the rename must NOT hand out a fresh quota.
        mockMvc.perform(get("/api/v1/settings").with(as.apply("name-after")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void edgeFilterKeysByActiveOrgFromTheOrganizationClaim() throws Exception {
        UUID orgId = UUID.randomUUID();
        RequestPostProcessor org = orgToken(orgId, "user");
        // A DIFFERENT user in the SAME org — proves the bucket is keyed by org, not by sub.
        RequestPostProcessor sameOrgOtherUser = orgToken(orgId, "someone-else");

        mockMvc.perform(get("/api/v1/settings").with(org)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings").with(org)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings").with(sameOrgOtherUser)).andExpect(status().isOk()); // 3rd of the org
        mockMvc.perform(get("/api/v1/settings").with(org))
                .andExpect(status().isTooManyRequests()); // 4th in the org -> throttled regardless of which user

        // A different org has its own bucket.
        mockMvc.perform(get("/api/v1/settings").with(orgToken(UUID.randomUUID(), "user")))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor orgToken(UUID orgId, String subject) {
        return jwt()
                .jwt(builder -> builder.subject(subject)
                        .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void egressChannelLimitDefersExcessDeliveries() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/rl", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/rl";
        String subject = "RL-" + UUID.randomUUID();
        try {
            List<Recipient> recipients = IntStream.range(0, 5)
                    .mapToObj(i -> Recipient.webhook(base + "?i=" + i))
                    .toList();
            notifications.dispatch(new NotificationRequest(subject, "x", recipients, Map.of()));
            worker.drainOnce();

            // WEBHOOK capacity is 2/min — only 2 deliver this window; the other 3 are deferred, not failed.
            assertThat(hits.get()).isEqualTo(2);
            assertThat(count("select count(*) from notification_delivery where subject = ? and status = 'SENT'", subject)).isEqualTo(2);
            assertThat(count("select count(*) from notification_delivery where subject = ? and status = 'PENDING'", subject)).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
