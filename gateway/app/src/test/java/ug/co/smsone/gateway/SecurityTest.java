package ug.co.smsone.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.netty.handler.codec.http.HttpHeaders;
import java.time.Instant;
import java.util.Date;
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
import reactor.netty.http.server.HttpServerRequest;

/**
 * Edge security: the gateway validates a bearer JWT against a JWKS (here an in-test RSA key, served
 * by the stub — the same production code path, deterministic claims) and applies each route's coarse
 * policy. Open routes need no token; a secured route without a valid token is 401; a missing scope or
 * a wrong tenant is 403; a valid request routes and the subject/tenant are stamped downstream. CORS
 * preflight is answered at the edge and security headers are present.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityTest {

    private static final RSAKey RSA_KEY;
    private static final String JWKS_JSON;
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
                                .header("X-Backend-Saw-Subject", headerOrNone(request, "X-Auth-Subject"))
                                .header("X-Backend-Saw-Tenant", headerOrNone(request, "X-Tenant-Id"))
                                .sendString(Mono.just("backend:" + request.uri()))))
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
            // A fresh connection per request — no pooling — so an error response's connection close
            // never resets a later reused connection under full-suite load.
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void openRouteNeedsNoToken() {
        client().get().uri("/open/x").exchange().expectStatus().isOk();
    }

    @Test
    void securedRouteWithoutTokenIs401() {
        client().get().uri("/secured/x").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void securedRouteWithValidTokenRoutesAndStampsSubject() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(token("user-1", "api", null, plus(300))))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Backend-Saw-Subject", "user-1");
    }

    @Test
    void expiredTokenIs401() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(token("user-1", "api", null, plus(-10))))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tamperedTokenIs401() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(token("user-1", "api", null, plus(300)) + "x"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void missingRequiredScopeIs403() {
        client().get().uri("/scoped/x").headers(h -> h.setBearerAuth(token("user-1", "other", null, plus(300))))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("FORBIDDEN");
    }

    @Test
    void presentScopePasses() {
        client().get().uri("/scoped/x").headers(h -> h.setBearerAuth(token("user-1", "api reports", null, plus(300))))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void wrongTenantIs403() {
        client().get().uri("/tenant/orgs/globex/data")
                .headers(h -> h.setBearerAuth(token("user-1", "api", "acme", plus(300))))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void matchingTenantPassesAndStampsTenant() {
        client().get().uri("/tenant/orgs/acme/data")
                .headers(h -> h.setBearerAuth(token("user-1", "api", "acme", plus(300))))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Backend-Saw-Tenant", "acme");
    }

    @Test
    void corsPreflightIsAnsweredAtTheEdge() {
        client().options().uri("/secured/x")
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://example.com");
    }

    @Test
    void responsesCarrySecurityHeaders() {
        client().get().uri("/open/x").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    private static Instant plus(long seconds) {
        return Instant.now().plusSeconds(seconds);
    }

    private static String token(String subject, String scope, String tenant, Instant expiry) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiry));
            if (scope != null) {
                claims.claim("scope", scope);
            }
            if (tenant != null) {
                claims.claim("tenant", tenant);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(RSA_KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String headerOrNone(HttpServerRequest request, String name) {
        HttpHeaders headers = request.requestHeaders();
        String value = headers.get(name);
        return value == null ? "none" : value;
    }
}
