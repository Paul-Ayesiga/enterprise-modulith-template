package ug.co.smsone.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistry;
import ug.co.smsone.gateway.core.security.EdgeOrganization;

/**
 * The decision table itself: which organization is served by which deployment, and what makes that
 * answer change without a restart.
 *
 * <p>The table is (configuration × the live service registry), and only the first half is fixed. The
 * registry is mutable at runtime — discovery registers and deregisters services — so the resolved
 * answer is a snapshot swapped whole on {@link RefreshRoutesEvent}, the same signal the route table
 * and the edge's policy map already rebuild on. Without that, a tenant deployment that arrives by
 * discovery would be refused until somebody restarted the gateway, which is exactly the class of
 * "correct but stale" failure a cutover cannot afford.
 */
class TenantUpstreamsTest {

    private static final ServiceDefinition TENANT = new ServiceDefinition("tenant-backend",
            List.of(URI.create("http://tenant.internal:8080")), "/health");
    private static final ServiceDefinition BALANCED = new ServiceDefinition("big-backend",
            List.of(URI.create("http://a.internal:8080"), URI.create("http://b.internal:8080")), "/health");

    @Test
    void aServiceThatArrivesAfterBootIsPickedUpOnTheNextRefresh() {
        MutableRegistry registry = new MutableRegistry();
        TenantUpstreams upstreams = new TenantUpstreams(
                properties(Map.of("tenant-alias", "tenant-backend")), registry);

        assertThat(upstreams.decide(new EdgeOrganization("tenant-alias", null)).isRefused())
                .as("nothing registers that service yet — refuse, never fall back")
                .isTrue();

        registry.put(TENANT);
        upstreams.onApplicationEvent(new RefreshRoutesEvent(this));

        assertThat(upstreams.decide(new EdgeOrganization("tenant-alias", null)).service())
                .as("the same signal the route table rebuilds on re-decides placement too")
                .isSameAs(TENANT);
    }

    @Test
    void aServiceThatGoesAwayStopsBeingServedRatherThanSilentlyReverting() {
        MutableRegistry registry = new MutableRegistry();
        registry.put(TENANT);
        TenantUpstreams upstreams = new TenantUpstreams(
                properties(Map.of("tenant-alias", "tenant-backend")), registry);
        assertThat(upstreams.decide(new EdgeOrganization("tenant-alias", null)).isDedicated()).isTrue();

        registry.remove("tenant-backend");
        upstreams.onApplicationEvent(new RefreshRoutesEvent(this));

        assertThat(upstreams.decide(new EdgeOrganization("tenant-alias", null)).isRefused())
                .as("a deregistered tenant deployment must NOT hand the tenant back to the shared one")
                .isTrue();
    }

    /** Both spellings the claim can carry resolve, so configuring either one is enough. */
    @Test
    void theAliasAndTheIdAreBothAcceptedRoutingKeys() {
        MutableRegistry registry = new MutableRegistry();
        registry.put(TENANT);
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("acme", "tenant-backend");
        configured.put("org-1", "tenant-backend");
        TenantUpstreams upstreams = new TenantUpstreams(properties(configured), registry);

        assertThat(upstreams.decide(new EdgeOrganization("acme", "unconfigured")).isDedicated()).isTrue();
        assertThat(upstreams.decide(new EdgeOrganization("unconfigured", "org-1")).isDedicated()).isTrue();
        assertThat(upstreams.decide(new EdgeOrganization("acme", "org-1")).isDedicated())
                .as("both configured and in agreement").isTrue();
    }

    /**
     * Configuration that disagrees with itself refuses instead of picking a side. Nothing at the edge
     * can tell which half is right, and serving either is a coin flip between "correct" and "this
     * tenant's rows are in the other database" — ADR 0011 §2.4's worst failure.
     */
    @Test
    void twoSpellingsPointingAtTwoDeploymentsRefuse() {
        MutableRegistry registry = new MutableRegistry();
        registry.put(TENANT);
        registry.put(BALANCED);
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("acme", "tenant-backend");
        configured.put("org-1", "big-backend");
        TenantUpstreams upstreams = new TenantUpstreams(properties(configured), registry);

        TenantUpstreams.Decision decision = upstreams.decide(new EdgeOrganization("acme", "org-1"));
        assertThat(decision.isRefused()).isTrue();
        assertThat(decision.refusalCode()).isEqualTo(TenantUpstreams.Decision.AMBIGUOUS_CONFIG);
    }

    @Test
    void anOrganizationWithNoEntryAndNoOrganizationAtAllAreBothShared() {
        MutableRegistry registry = new MutableRegistry();
        registry.put(TENANT);
        TenantUpstreams upstreams = new TenantUpstreams(
                properties(Map.of("acme", "tenant-backend")), registry);

        assertThat(upstreams.decide(new EdgeOrganization("globex", "org-9")).kind())
                .as("every tenant today").isEqualTo(TenantUpstreams.Decision.Kind.SHARED);
        assertThat(upstreams.decide(EdgeOrganization.NONE).kind())
                .as("the public surface, and any caller the edge could not place")
                .isEqualTo(TenantUpstreams.Decision.Kind.SHARED);
        assertThat(upstreams.decide(null).kind()).isEqualTo(TenantUpstreams.Decision.Kind.SHARED);
    }

    /** The table an operator watches during a cutover has to distinguish the two states that matter. */
    @Test
    void theAdminTableReportsResolvedAndUnresolvedPlacements() {
        MutableRegistry registry = new MutableRegistry();
        registry.put(BALANCED);
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("acme", "big-backend");
        configured.put("ghost", "no-such-service");
        TenantUpstreams upstreams = new TenantUpstreams(properties(configured), registry);

        Map<String, Map<String, Object>> table = upstreams.table();
        assertThat(table.keySet()).as("ordered as configured, so it reads like the file")
                .containsExactly("acme", "ghost");
        assertThat(table.get("acme")).containsEntry("resolved", true).containsEntry("loadBalanced", true);
        assertThat(table.get("acme").get("instances")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(2);
        assertThat(table.get("ghost")).containsEntry("resolved", false);
    }

    /**
     * The mis-keyed cutover, and the only signal that separates it from a tenant nobody has moved.
     *
     * <p>Three id-spaces can name one organization and only two of them ever reach the edge: a JWT
     * carries the Keycloak ALIAS and the PROVIDER id inside its claim, an API key introspects to the
     * platform's own {@code organization.id}. {@code platform.tenant_placement} is keyed on that third
     * one — and it is the only spelling written down anywhere, so it is the one an operator copies. The
     * entry then resolves, reports healthy, and matches nobody: every JWT caller for that tenant falls
     * through to the shared deployment, whose database no longer holds its rows. Nothing else in this
     * class can tell that apart from a tenant with no entry, because from in here they are identical.
     */
    @Test
    void aKeyNoCredentialCarriesResolvesPerfectlyAndIsVisibleOnlyAsZeroMatches() {
        MutableRegistry registry = new MutableRegistry();
        registry.put(TENANT);
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("acme", "tenant-backend");                     // the alias the token carries
        configured.put("6f9a…-platform-org-id", "tenant-backend");    // what tenant_placement is keyed on
        TenantUpstreams upstreams = new TenantUpstreams(properties(configured), registry);

        upstreams.decide(new EdgeOrganization("acme", "01H8-keycloak-provider-id"));
        upstreams.decide(new EdgeOrganization("acme", "01H8-keycloak-provider-id"));

        Map<String, Map<String, Object>> table = upstreams.table();
        assertThat(table.get("acme")).containsEntry("matches", 2L);
        assertThat(table.get("acme").get("lastMatched")).isNotNull();
        assertThat(table.get("6f9a…-platform-org-id"))
                .as("healthy on every other column — resolved says nothing about the key")
                .containsEntry("resolved", true)
                .containsEntry("matches", 0L);
        assertThat(table.get("6f9a…-platform-org-id").get("lastMatched")).isNull();
    }

    /**
     * "Did any credential carry this spelling" and "does the upstream resolve" are different questions,
     * and a cutover needs both answered separately: a key that matched and was then refused has proved
     * the spelling right and the service wrong, which is a one-line fix rather than a re-keying.
     */
    @Test
    void aKeyIsCreditedForMatchingEvenWhenItsUpstreamIsRefused() {
        MutableRegistry registry = new MutableRegistry();
        TenantUpstreams upstreams = new TenantUpstreams(
                properties(Map.of("acme", "no-such-service")), registry);

        assertThat(upstreams.decide(new EdgeOrganization("acme", null)).refusalCode())
                .isEqualTo(TenantUpstreams.Decision.UNRESOLVED_SERVICE);

        assertThat(upstreams.table().get("acme"))
                .containsEntry("resolved", false)
                .containsEntry("matches", 1L);
    }

    /** A tenant with no entry must not grow one — the counter map is the configured keys, forever. */
    @Test
    void thePooledMajorityIsCountedNowhere() {
        MutableRegistry registry = new MutableRegistry();
        registry.put(TENANT);
        TenantUpstreams upstreams = new TenantUpstreams(
                properties(Map.of("acme", "tenant-backend")), registry);

        for (int i = 0; i < 50; i++) {
            upstreams.decide(new EdgeOrganization("pooled-" + i, "org-" + i));
        }

        assertThat(upstreams.table().keySet())
                .as("a per-organization counter on a gateway fronting every tenant is an unbounded map")
                .containsExactly("acme");
        assertThat(upstreams.table().get("acme")).containsEntry("matches", 0L);
    }

    /**
     * Malformed configuration fails at STARTUP — the one failure that is not per-tenant, because a
     * blank entry cannot be repaired by anything happening later (ADR 0011 §4.2's split).
     */
    @Test
    void anEntryNamingNoServiceFailsAtStartupRatherThanSilentlySharing() {
        Map<String, String> blank = new LinkedHashMap<>();
        blank.put("acme", " ");

        assertThatThrownBy(() -> properties(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acme");
    }

    private static TenantUpstreamProperties properties(Map<String, String> upstreams) {
        return new TenantUpstreamProperties(upstreams, Duration.ofSeconds(30));
    }

    /** Stands in for the live registry, which discovery mutates while the gateway is running. */
    private static final class MutableRegistry implements ServiceRegistry {

        private final ConcurrentMap<String, ServiceDefinition> services = new ConcurrentHashMap<>();

        void put(ServiceDefinition service) {
            services.put(service.id(), service);
        }

        void remove(String serviceId) {
            services.remove(serviceId);
        }

        @Override
        public Optional<ServiceDefinition> find(String serviceId) {
            return Optional.ofNullable(services.get(serviceId));
        }

        @Override
        public List<ServiceDefinition> all() {
            return List.copyOf(services.values());
        }
    }
}
