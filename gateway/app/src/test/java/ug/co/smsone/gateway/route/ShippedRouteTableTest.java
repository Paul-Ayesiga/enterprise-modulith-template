package ug.co.smsone.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.AntPathMatcher;
import ug.co.smsone.gateway.config.GatewayProperties;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;

/**
 * Guards the route table we actually ship in {@code src/main/resources/application.yml}.
 *
 * <p>Every other test in this module binds {@code src/test/resources/application.yml}, which declares its
 * own routes and policies. That left the real table untested, and a whole class of bug slipped through:
 * the modulith deliberately permits five pre-authentication paths, but the edge had no route for any of
 * them, so all five fell through to the {@code platform-api} catch-all ({@code /api/v1/**}, authenticated)
 * and answered 401. Self-service signup was unreachable, and so were the Pesapal and Kill Bill callbacks —
 * external systems that cannot present a JWT, which quietly broke payment confirmation.
 *
 * <p>These assertions therefore run against the shipped file, and they lean on route ordering rather than
 * looking routes up by id: {@link #route} takes the FIRST match by {@code order}, exactly as the runtime
 * does. If a public route were ever removed, renamed, or given an order above the catch-all, the catch-all
 * would win the match again and {@code requiresToken()} would flip back to true here.
 */
class ShippedRouteTableTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final StandardEnvironment ENV = shippedEnvironment();
    private static final List<RouteDefinition> ROUTES = shippedRoutes();

    /**
     * The edge must decide how far to trust {@code X-Forwarded-For} in exactly one place —
     * {@code gateway.security.blocklist.trusted-proxy-hops} — so the framework must not also have an
     * opinion. Left unset, Spring Boot derives the strategy from the detected cloud platform and turns
     * forwarded-header handling ON under Kubernetes, which rewrites {@code getRemoteAddress()} from the
     * caller's own header. {@code EdgeClientIp} then reads a spoofed peer while believing it is reading
     * the socket. Verified against the deployed gateway: with {@code 9.0.0.0/8} blocked, a request
     * carrying {@code X-Forwarded-For: 9.9.9.9} was refused 403 while the same request without the
     * header passed — blocklist evasion, and a per-IP rate limit and auto-block that anyone can aim.
     */
    @Test
    void forwardedHeadersAreNeverTrustedByTheFramework() {
        assertThat(ENV.getProperty("server.forward-headers-strategy"))
                .as("server.forward-headers-strategy must be pinned to none — see the gateway's application.yml")
                .isEqualToIgnoringCase("none");
    }

    /** Public by nature: a prospect has no account yet. */
    @Test
    void selfServiceSignupNeedsNoToken() {
        assertThat(route("/api/v1/signup").auth().requiresToken())
                .as("POST /api/v1/signup must be reachable pre-authentication")
                .isFalse();
        assertThat(route("/api/v1/signup/verify").auth().requiresToken())
                .as("GET+POST /api/v1/signup/verify must be reachable pre-authentication")
                .isFalse();
    }

    /** Public by necessity: these callers are external systems that never hold one of our tokens. */
    @Test
    void providerCallbacksNeedNoToken() {
        for (String path : List.of("/api/v1/payments/pesapal/ipn",
                "/api/v1/payments/pesapal/callback",
                "/api/v1/billing/killbill/notifications")) {
            assertThat(route(path).auth().requiresToken())
                    .as("%s is a provider callback and cannot carry a JWT", path)
                    .isFalse();
        }
    }

    /**
     * Opening those paths must not have opened the API. The catch-all still demands a token, so a path
     * that is merely ungrouped stays closed rather than becoming public by accident.
     */
    @Test
    void everythingElseStillNeedsAToken() {
        for (String path : List.of("/api/v1/me", "/api/v1/orgs/acme/members",
                "/api/v1/not-grouped-into-any-product", "/api/v1/signups-lookalike")) {
            assertThat(route(path).auth().requiresToken())
                    .as("%s must stay authenticated", path)
                    .isTrue();
        }
    }

    /**
     * The public routes traded authentication for tighter traffic limits, not for none. An unauthenticated
     * path is the one that most needs a rate limit, so losing it would be a real regression.
     */
    @Test
    void publicRoutesStillRateLimitAndCapTheBody() {
        for (String path : List.of("/api/v1/signup", "/api/v1/payments/pesapal/ipn")) {
            RouteDefinition route = route(path);
            assertThat(route.traffic().rateLimited()).as("%s must stay rate limited", path).isTrue();
            assertThat(route.traffic().maxRequestBytes())
                    .as("%s should cap the body well below the authenticated 10 MB", path)
                    .isNotNull()
                    .isLessThanOrEqualTo(1_048_576L);
        }
    }

    /** The first route matching {@code path} by ascending {@code order} — the runtime's own resolution. */
    private static RouteDefinition route(String path) {
        return ROUTES.stream()
                .filter(route -> route.predicates().stream()
                        .filter(predicate -> predicate.kind() == RoutePredicate.Kind.PATH)
                        .anyMatch(predicate -> predicate.args().stream()
                                .anyMatch(pattern -> MATCHER.match(pattern, path))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no shipped route matches " + path));
    }

    /** Loads the shipped application.yml into an Environment — deliberately NOT the test resources copy. */
    private static StandardEnvironment shippedEnvironment() {
        // Gradle runs tests with the module as the working directory; tolerate a repo-root runner too.
        Resource yaml = List.of(
                        new FileSystemResource("src/main/resources/application.yml"),
                        new FileSystemResource("gateway/app/src/main/resources/application.yml"))
                .stream()
                .filter(Resource::exists)
                .findFirst()
                .orElseThrow(() -> new AssertionError("could not locate the shipped application.yml"));

        StandardEnvironment environment = new StandardEnvironment();
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load("shipped", yaml)) {
                environment.getPropertySources().addFirst(source);
            }
        } catch (IOException e) {
            throw new AssertionError("could not read " + yaml, e);
        }
        return environment;
    }

    /** Runs the shipped config through the production RouteCatalog mapping. */
    private static List<RouteDefinition> shippedRoutes() {
        GatewayProperties properties = Binder.get(ENV)
                .bind("gateway", GatewayProperties.class)
                .orElseThrow(() -> new AssertionError("no gateway.* config in the shipped application.yml"));

        return new RouteCatalog(properties, event -> { }).routes();
    }
}
