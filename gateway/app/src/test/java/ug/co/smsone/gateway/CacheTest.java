package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/**
 * Edge response caching, and its tenant safety. The backend increments a counter on every real call and
 * returns it, so a stable body across requests proves a cache hit (the backend was not called again).
 * The cache-route is authenticated, so the edge stamps X-Tenant-Id from the token's tenant claim, which
 * the tenant-aware key generator folds into the key: the same tenant is served from cache, while a
 * different tenant must miss and reach the backend — never reading the first tenant's cached response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CacheTest {

    private static final RSAKey RSA_KEY;
    private static final String JWKS_JSON;
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger(0);
    private static final DisposableServer SERVER;

    static {
        try {
            RSA_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS_JSON = new JWKSet(RSA_KEY.toPublicJWK()).toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        SERVER = HttpServer.create().port(0)
                .route(routes -> routes
                        .get("/jwks", (request, response) -> response
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just(JWKS_JSON)))
                        .route(request -> true, (request, response) -> response.status(200)
                                .sendString(Mono.just("call-" + BACKEND_CALLS.incrementAndGet()))))
                .bindNow();
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void stubUris(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + SERVER.port());
        registry.add("jwks.uri", () -> "http://localhost:" + SERVER.port() + "/jwks");
    }

    @AfterAll
    static void stopServer() {
        SERVER.disposeNow();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void sameTenantIsServedFromCache() {
        String cached = awaitCached("/cache/same", "acme");
        assertThat(get("/cache/same", "acme")).as("still served from cache").isEqualTo(cached);
        assertThat(get("/cache/same", "acme")).as("still served from cache").isEqualTo(cached);
    }

    @Test
    void differentTenantNeverReadsAnothersCachedResponse() {
        String acme = awaitCached("/cache/isolation", "acme");
        assertThat(get("/cache/isolation", "globex"))
                .as("a different tenant misses and reaches the backend")
                .isNotEqualTo(acme);
        // And globex settles into its OWN partition — never acme's entry.
        assertThat(awaitCached("/cache/isolation", "globex"))
                .as("globex is cached separately from acme")
                .isNotEqualTo(acme);
    }

    /**
     * Returns the body once the edge is demonstrably serving it from cache.
     *
     * <p>The cache is written asynchronously, after the response has already gone back to the client, so
     * a request issued immediately after the first can still miss and reach the backend. The bodies then
     * differ and an eager assertion fails — which is exactly how this test passed on a laptop and failed
     * under CI load, where the write loses that race more often.
     *
     * <p>Waiting does not weaken what is being tested. The stub increments a counter and returns a fresh
     * {@code call-N} on every request that actually reaches it, so two consecutive identical bodies are
     * only possible when the cache served at least one of them. A gateway that never cached would return
     * a new value every time and this would time out rather than pass.
     */
    private String awaitCached(String path, String tenant) {
        String previous = get(path, tenant);
        for (int attempt = 0; attempt < 50; attempt++) { // ~5s ceiling
            String current = get(path, tenant);
            if (current.equals(previous)) {
                return current;
            }
            previous = current;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("responses never stabilised for tenant '" + tenant
                + "' — the edge is not caching " + path);
    }

    private String get(String path, String tenant) {
        return client().get().uri(path)
                .headers(h -> h.setBearerAuth(token("user-1", "api", tenant, Instant.now().plusSeconds(300))))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody().blockFirst();
    }

    private static String token(String subject, String scope, String tenant, Instant expiry) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiry))
                    .claim("scope", scope)
                    .claim("tenant", tenant);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(RSA_KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
