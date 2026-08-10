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
import ug.co.smsone.testsupport.EdgeSeed;

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
    @SuppressWarnings("resource") // Testcontainers owns this lifecycle — see AbstractIntegrationTest.POSTGRES
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

    /** For building limiters that differ from the wired one only in their deployment identity. */
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<io.lettuce.core.RedisClient> redisClients;

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

    /**
     * <b>ADR 0010 §6 hop 2→3: two deployments on ONE Valkey do not share a tenant's quota.</b> The two
     * limiters below are the production class over the same connection, differing only in
     * {@code app.deployment.id} — so the isolation asserted here is the one an extracted deployment
     * actually gets, not a property of a key the test built itself.
     *
     * <p>The failure it forbids is quiet in both directions and neither side logs anything: a tenant
     * whose organization id survives an extraction unchanged (§2.2) would have one bucket spent by two
     * deployments, so the platform throttles it for traffic the extracted deployment served, and the
     * extracted deployment throttles it for traffic the platform served. The third assertion is the
     * falsifier — with the SAME identity the same key IS one bucket, so the first two are not passing
     * because the keys happened to differ.
     */
    @Test
    void twoDeploymentsSharingOneValkeyDoNotShareARateLimitBucket() {
        String key = "rl:write:tenant:" + UUID.randomUUID();
        DistributedRateLimiter platform = limiterFor(
                ug.co.smsone.shared.deployment.DeploymentIdentity.PLATFORM);
        DistributedRateLimiter extracted = limiterFor("acme");
        // A SECOND instance of the same deployment, held back as the falsifier below.
        DistributedRateLimiter extractedPeer = limiterFor("acme");
        try {
            assertThat(platform.tryConsume(key, 1, Duration.ofMinutes(1), false).allowed()).isTrue();
            assertThat(platform.tryConsume(key, 1, Duration.ofMinutes(1), false).allowed())
                    .as("the platform's own bucket is spent")
                    .isFalse();
            assertThat(extracted.tryConsume(key, 1, Duration.ofMinutes(1), false).allowed())
                    .as("the extracted deployment's bucket for the SAME tenant key is untouched")
                    .isTrue();

            assertThat(extractedPeer.tryConsume(key, 1, Duration.ofMinutes(1), false).allowed())
                    .as("same deployment id, different instance, same key: ONE bucket — so the"
                            + " isolation above is the deployment's doing and not the test's, and not"
                            + " an artefact of each limiter holding its own connection")
                    .isFalse();
        } finally {
            // Hand-built limiters get no @PreDestroy, and each holds a Lettuce connection for the rest
            // of the JVM's life otherwise — the suite shares one container across every cached context.
            platform.closeConnection();
            extracted.closeConnection();
            extractedPeer.closeConnection();
        }
    }

    private DistributedRateLimiter limiterFor(String deploymentId) {
        return new DistributedRateLimiter(redisClients,
                new ug.co.smsone.shared.deployment.DeploymentIdentity(deploymentId));
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
     * With no tenant to key on, the bucket falls back to the principal — which must be the caller's
     * durable identity, not {@code preferred_username}. A name-keyed bucket is escapable (rename
     * yourself for a fresh quota) and inheritable (a recycled username lands in the previous holder's
     * bucket). Built through the real converter, which is what makes principal name differ from subject.
     *
     * <p>That durable identity is now {@code person:<uuid>}, reached through {@code external_identity}
     * — so the person and the link are seeded and the token carries {@code iss}. Without them
     * {@code currentPrincipalKey()} is empty, the resolver degrades past the principal to the CLIENT IP,
     * and this test silently proved nothing: 127.0.0.1 is the same key for every request in the class,
     * so both names landed in one bucket whatever the resolver keyed on.
     */
    @Test
    void principalFallbackKeysBySubjectNotUsername() throws Exception {
        var converter = new ug.co.smsone.shared.security.KeycloakJwtAuthenticationConverter();
        String subject = "sub-" + UUID.randomUUID();
        EdgeSeed.person(jdbc, subject);

        java.util.function.Function<String, RequestPostProcessor> as = username ->
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .authentication(converter.convert(org.springframework.security.oauth2.jwt.Jwt
                                .withTokenValue("token").header("alg", "RS256")
                                .subject(subject)
                                .claim("iss", EdgeSeed.ISSUER)
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
        // A tenant the EDGE can actually resolve. A bare random UUID in the claim names no
        // organization: OrgResolver finds no external_organization link, CurrentUser.organizationId
        // is null, and a TENANT-scoped tier then degrades past the (absent) flat tenant claim and the
        // (absent) principal all the way to the CLIENT IP — 127.0.0.1 for every MockMvc request in
        // this class, so this test was sharing one 3-request bucket with its siblings and 429ing on
        // its first call. Seeding the org is what makes the key an org key at all.
        UUID orgId = seedOrg();
        RequestPostProcessor org = orgToken(orgId, "user");
        // A DIFFERENT user in the SAME org — proves the bucket is keyed by org, not by sub.
        RequestPostProcessor sameOrgOtherUser = orgToken(orgId, "someone-else");

        mockMvc.perform(get("/api/v1/settings").with(org)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings").with(org)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings").with(sameOrgOtherUser)).andExpect(status().isOk()); // 3rd of the org
        mockMvc.perform(get("/api/v1/settings").with(org))
                .andExpect(status().isTooManyRequests()); // 4th in the org -> throttled regardless of which user

        // A different org has its own bucket — seeded too, for the same reason.
        mockMvc.perform(get("/api/v1/settings").with(orgToken(seedOrg(), "user")))
                .andExpect(status().isOk());
    }

    /** An organization plus the provider link a token's {@code organization} claim resolves through. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * A token whose {@code organization} claim resolves to {@code orgId}: {@code iss} byte-identical to
     * what the link was seeded with (the resolver takes the issuer from the TOKEN, and a null one
     * short-circuits before the lookup), and the alias-keyed claim rebuilt from
     * {@code external_organization} rather than spelled by hand — the claim carries the PROVIDER's id
     * and alias, which are not the local {@code organization.id}.
     */
    private RequestPostProcessor orgToken(UUID orgId, String subject) {
        Map<String, Object> claim = orgClaim(orgId);
        return jwt()
                .jwt(builder -> builder.claim("iss", EdgeSeed.ISSUER).subject(subject)
                        .claim("organization", claim))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private Map<String, Object> orgClaim(UUID orgId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return Map.of(String.valueOf(link.get("external_alias")),
                Map.of("id", String.valueOf(link.get("external_org_id"))));
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
