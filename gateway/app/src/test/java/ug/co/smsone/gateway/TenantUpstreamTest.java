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
import java.util.LinkedHashMap;
import java.util.Map;
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
 * ADR 0010 §7 Phase 8 at the edge: the gateway routes by the caller's ORGANIZATION claim, so a tenant
 * that has been moved onto a deployment of its own reaches it, and everybody else reaches the shared
 * modulith exactly as before.
 *
 * <p>Two real backends run here — SHARED and TENANT — and every assertion reads which one answered out
 * of the body rather than out of the gateway's own report, because a routing test that trusts the
 * router has not tested the routing. The refusals are asserted the same way: a 503 is only interesting
 * if it is provably NOT the shared deployment quietly serving a tenant whose rows have moved, so each
 * one checks the body is the edge's envelope and not {@code shared:…}.
 *
 * <p>The two broken placements are configured in {@code src/test/resources/application.yml} and are
 * broken in the two distinct ways that can actually happen: {@code ghost-alias} names a service no
 * {@code gateway.services} entry registers (a typo, or a half-applied cutover), and
 * {@code unreachable-alias} names one that is registered but is not listening (the deployment is down,
 * or has not been rolled yet).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantUpstreamTest {

    private static final String GATEWAY_SECRET = "tenant-test-secret";
    private static final RSAKey RSA_KEY;
    private static final String JWKS_JSON;
    /** The shared modulith — and, for convenience, the JWKS and the API-key introspection stub. */
    private static final DisposableServer SHARED;
    /** One tenant's own deployment. Answering with its own name is the whole assertion. */
    private static final DisposableServer TENANT;

    static {
        try {
            RSA_KEY = new RSAKeyGenerator(2048).keyID("tenant-key").generate();
            JWKS_JSON = new JWKSet(RSA_KEY.toPublicJWK()).toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        SHARED = HttpServer.create().port(0)
                .route(routes -> routes
                        .get("/jwks", (request, response) -> response
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just(JWKS_JSON)))
                        // Drains the request body before answering — an unread body leaves the
                        // connection wedged, which shows up as a hang rather than a failure.
                        .post("/introspect", (request, response) -> request.receive().aggregate().asString()
                                .defaultIfEmpty("")
                                .flatMap(body -> response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"active\":true,\"subject\":\"key:1\","
                                                + "\"tenant\":\"org-key-tenant\",\"scopes\":[\"api\"]}"))
                                        .then()))
                        .route(request -> true, (request, response) -> response.status(200)
                                .sendString(Mono.just("shared:" + request.uri()))))
                .bindNow();
        TENANT = HttpServer.create().port(0)
                .handle((request, response) -> response.status(200)
                        .sendString(Mono.just("tenant:" + request.uri())))
                .bindNow();
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + SHARED.port());
        registry.add("jwks.uri", () -> "http://localhost:" + SHARED.port() + "/jwks");
        registry.add("tenant.uri", () -> "http://localhost:" + TENANT.port());
        registry.add("gateway.platform.introspection.uri",
                () -> "http://localhost:" + SHARED.port() + "/introspect");
        registry.add("gateway.platform.secret", () -> GATEWAY_SECRET);
    }

    @AfterAll
    static void stop() {
        SHARED.disposeNow();
        TENANT.disposeNow();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    // --- the tenant with a deployment of its own reaches it --------------------------------------

    @Test
    void anOrganizationWithItsOwnDeploymentReachesIt() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(tokenFor("tenant-alias", "org-11111111")))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Gateway-Tenant-Upstream", "tenant-backend")
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("tenant:/secured/x"));
    }

    /**
     * The claim is a map KEYED BY ALIAS carrying an id inside it, and an operator may have written
     * down either. Configuring one spelling must not leave the other silently on the shared
     * deployment — this token's alias is not configured and its id is.
     */
    @Test
    void theIdSpellingOfTheClaimRoutesTheSameAsTheAlias() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(tokenFor("not-configured", "org-11111111")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("tenant:/secured/x"));
    }

    /** The agent surface holds an API key, not a JWT; introspection is where its organization comes from. */
    @Test
    void anApiKeyRoutesToItsOrganizationsDeploymentToo() {
        client().get().uri("/secured/x").header("X-Api-Key", "sk_agent.secret").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Gateway-Tenant-Upstream", "tenant-backend")
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("tenant:/secured/x"));
    }

    // --- everybody else is untouched, which is every tenant today -------------------------------

    @Test
    void anOrganizationWithNoDedicatedDeploymentReachesTheSharedOne() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(tokenFor("globex", "org-99999999")))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("X-Gateway-Tenant-Upstream")
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("shared:/secured/x"));
    }

    @Test
    void aTokenNamingNoOrganizationReachesTheSharedOne() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(token(claims -> { })))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("shared:/secured/x"));
    }

    /**
     * A person seated in several organizations holds ONE token for all of them, so the claim names no
     * single tenant. Picking the first entry would route the other tenants to a deployment that does
     * not hold their rows — the misroute ADR 0011 §2.4 calls the worst failure available — so a
     * multi-organization claim places nobody and the shared deployment answers.
     */
    @Test
    void aTokenNamingSeveralOrganizationsPlacesNobody() {
        Map<String, Object> several = new LinkedHashMap<>();
        several.put("tenant-alias", Map.of("id", "org-11111111"));
        several.put("globex", Map.of("id", "org-99999999"));
        client().get().uri("/secured/x")
                .headers(h -> h.setBearerAuth(token(claims -> claims.claim("organization", several))))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("shared:/secured/x"));
    }

    // --- a broken placement refuses; it never falls back ----------------------------------------

    @Test
    void anUnreachableDedicatedDeploymentIs503AndNeverFallsBackToTheSharedOne() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(tokenFor("unreachable-alias", null)))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "7")
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.errors[0].requestId").exists();
    }

    /**
     * The other half of the same rule, and the one a typo produces: the placement names a service the
     * gateway does not have. It is refused per tenant — never at boot, because a gateway that refuses
     * to start takes every OTHER tenant down with it (ADR 0011 §4.2 decided this one layer below).
     */
    @Test
    void aDedicatedDeploymentTheGatewayDoesNotKnowIs503() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(tokenFor("ghost-alias", null)))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "7")
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    /**
     * Configuration that disagrees with itself — the token's two spellings of one organization mapped
     * to two deployments. Nothing at the edge can tell which is right, so serving either is a coin
     * flip between "correct" and "this tenant's rows are in the other database".
     */
    @Test
    void anOrganizationConfiguredToTwoDeploymentsIs503RatherThanAGuess() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(tokenFor("split-alias", "org-22222222")))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    /**
     * The same rule on a route carrying {@code circuit-breaker: true} — the one shape that quietly
     * bypassed it. Spring Cloud's circuit-breaker filter is route-scoped, so it runs INSIDE the global
     * placement filter, and with a fallback URI configured it catches the connect failure, forwards to
     * {@code /__fallback} and completes normally: the refusal contract vanishes, replaced by a bare 503.
     * Worse, the breaker is named per ROUTE while the retarget deliberately keeps the route id, so one
     * dead tenant deployment's failures accumulate on the instance every OTHER tenant on that path
     * shares — 'refuses per pod', which is exactly what ADR 0011 §4.2 forbids.
     *
     * <p>Five failures is comfortably past {@code ResilienceConfig}'s window (4 calls, 50%), so the
     * final assertion is a real falsifier and not a hopeful one.
     */
    @Test
    void aDeadTenantDeploymentKeepsItsRefusalAndNeverOpensTheSharedRoutesCircuit() {
        for (int attempt = 0; attempt < 5; attempt++) {
            client().get().uri("/cbtenant/x")
                    .headers(h -> h.setBearerAuth(tokenFor("unreachable-alias", null)))
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    // Both of these are absent from the circuit breaker's fallback, which is what makes
                    // them the assertion: it answers a bare 503 with no envelope and no Retry-After.
                    .expectHeader().valueEquals("Retry-After", "7")
                    .expectBody().jsonPath("$.errors[0].requestId").exists();
        }

        client().get().uri("/cbtenant/x").headers(h -> h.setBearerAuth(tokenFor("globex", null)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("a pooled tenant on the same route, after five failures belonging to another "
                                + "tenant's deployment entirely")
                        .isEqualTo("shared:/cbtenant/x"));
    }

    /** The refusal is this tenant's alone — the pooled majority is served through the same gateway. */
    @Test
    void aRefusedTenantDoesNotAffectAnybodyElse() {
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(tokenFor("ghost-alias", null)))
                .exchange().expectStatus().isEqualTo(503);
        client().get().uri("/secured/x").headers(h -> h.setBearerAuth(tokenFor("globex", null)))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("shared:/secured/x"));
    }

    // --- the pre-authentication surface is untouched ---------------------------------------------

    /**
     * {@code /api/v1/signup}, the Pesapal IPN/callback and the Kill Bill notification carry no token
     * and therefore no claim; here {@code /open/**} stands for them, since the shipped table's public
     * routes are the same shape (see {@code ShippedRouteTableTest}). The strong form of the rule is
     * asserted: even a caller who DOES hold a token for a tenant with its own deployment stays on the
     * shared one, because a route the edge does not authenticate names no organization at all.
     */
    @Test
    void anUnauthenticatedRouteIsUnaffectedEvenWhenTheCallerHoldsATenantToken() {
        client().get().uri("/open/x").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("shared:/open/x"));

        client().get().uri("/open/x").headers(h -> h.setBearerAuth(tokenFor("tenant-alias", "org-11111111")))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("X-Gateway-Tenant-Upstream")
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("shared:/open/x"));
    }

    /** No token at all on a secured route is still 401 — placement decides nothing about admission. */
    @Test
    void anUnauthenticatedCallerOnASecuredRouteIsStill401() {
        client().get().uri("/secured/x").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("UNAUTHORIZED");
    }

    // --- the table an operator reads during a cutover ---------------------------------------------

    /**
     * {@code resolved} answers a question about the SERVICE. The half a cutover actually gets wrong is
     * the KEY — an entry spelled in an id-space no credential carries resolves perfectly and matches
     * nobody — so the table has to report that a real caller reached each key. The routed request first,
     * so the assertion does not depend on which test in this class ran before it.
     */
    @Test
    void theAdminTableShowsWhichPlacementsResolveAndWhichKeysCallersActuallyCarry() {
        client().get().uri("/secured/x")
                .headers(h -> h.setBearerAuth(tokenFor("tenant-alias", "org-11111111")))
                .exchange().expectStatus().isOk();

        client().get().uri("/actuator/gatewaytenants").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$['tenant-alias'].serviceId").isEqualTo("tenant-backend")
                .jsonPath("$['tenant-alias'].resolved").isEqualTo(true)
                .jsonPath("$['tenant-alias'].lastMatched").exists()
                .jsonPath("$['ghost-alias'].resolved").isEqualTo(false);
    }

    private static String tokenFor(String alias, String id) {
        Map<String, Object> organization = Map.of(alias,
                id == null ? Map.of() : Map.of("id", id));
        return token(claims -> claims.claim("organization", organization));
    }

    private static String token(java.util.function.Consumer<JWTClaimsSet.Builder> customizer) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject("user-1")
                    .claim("scope", "api")
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            customizer.accept(claims);
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
