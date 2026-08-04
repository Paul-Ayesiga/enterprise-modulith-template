package ug.co.smsone.gateway.route;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import ug.co.smsone.gateway.core.lifecycle.LifecyclePolicy;
import ug.co.smsone.gateway.core.lifecycle.RouteLifecycle;
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteRegistrar;
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.core.traffic.TrafficPolicy;
import ug.co.smsone.gateway.core.transform.TransformPolicy;

/**
 * Makes UI-registered routes DURABLE — they survive a gateway restart, unlike the plain runtime tier
 * (which is re-seeded from YAML on every boot). The UI-settable route spec (path, service, order,
 * auth, rate-limit, lifecycle) is stored in Valkey; on {@link ApplicationReadyEvent} the persisted
 * routes are OVERLAID on top of the YAML seed, so YAML stays the reviewable baseline and durable
 * operator routes ride on top. Present only when {@code gateway.persistent-routes.enabled} is not
 * turned off. Best-effort against Valkey — a blip logs and the seed still serves.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.persistent-routes", name = "enabled",
        matchIfMissing = true, havingValue = "true")
public class PersistentRouteStore {

    private static final Logger log = LoggerFactory.getLogger(PersistentRouteStore.class);
    private static final String IDS_KEY = "gwroutes:persistent:ids";
    private static final String DEF_PREFIX = "gwroutes:persistent:def:";

    private final ReactiveStringRedisTemplate redis;
    private final RouteRegistrar registrar;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile Set<String> ids = Set.of();

    /** The durable subset — exactly what the admin UI can set on a route. */
    record PersistedRoute(String id, String path, String serviceId, int order, boolean authenticated,
            boolean rateLimited, String lifecycle, String sunset) {
    }

    PersistentRouteStore(ReactiveStringRedisTemplate redis, RouteRegistrar registrar) {
        this.redis = redis;
        this.registrar = registrar;
    }

    public boolean isPersistent(String id) {
        return ids.contains(id);
    }

    /** Persist (or re-persist) a route so it comes back after a restart. Path-predicate routes only. */
    public void save(RouteDefinition route) {
        PersistedRoute persisted = toPersisted(route);
        if (persisted == null) {
            log.warn("Route '{}' has no simple PATH predicate — not persisted", route.id());
            return;
        }
        String body = json.writeValueAsString(persisted);
        redis.opsForValue().set(DEF_PREFIX + route.id(), body).subscribe(x -> { }, e ->
                log.warn("persist route '{}' to Valkey failed (runtime-only until refresh): {}", route.id(), e.toString()));
        redis.opsForSet().add(IDS_KEY, route.id()).subscribe(x -> { }, e -> { });
        Set<String> next = new LinkedHashSet<>(ids);
        next.add(route.id());
        ids = next;
    }

    public void delete(String id) {
        redis.opsForValue().delete(DEF_PREFIX + id).subscribe(x -> { }, e ->
                log.warn("un-persist route '{}' from Valkey failed: {}", id, e.toString()));
        redis.opsForSet().remove(IDS_KEY, id).subscribe(x -> { }, e -> { });
        if (ids.contains(id)) {
            Set<String> next = new LinkedHashSet<>(ids);
            next.remove(id);
            ids = next;
        }
    }

    /** Overlay durable routes on top of the YAML seed once the context is ready. */
    @EventListener(ApplicationReadyEvent.class)
    void hydrate() {
        redis.opsForSet().members(IDS_KEY)
                .flatMap(id -> redis.opsForValue().get(DEF_PREFIX + id))
                .collectList()
                .subscribe(bodies -> {
                    Set<String> loaded = new LinkedHashSet<>();
                    for (String body : bodies) {
                        try {
                            PersistedRoute persisted = json.readValue(body, PersistedRoute.class);
                            registrar.register(toDefinition(persisted));
                            loaded.add(persisted.id());
                        } catch (RuntimeException e) {
                            log.warn("skipping unreadable persisted route: {}", e.toString());
                        }
                    }
                    ids = loaded;
                    if (!loaded.isEmpty()) {
                        log.info("Hydrated {} durable route(s) over the YAML seed: {}", loaded.size(), loaded);
                    }
                }, e -> log.warn("persistent route hydrate failed — YAML seed stands: {}", e.toString()));
    }

    /** Keep the id snapshot converged with Valkey (for the admin badge); routes register at boot only. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void refreshIds() {
        redis.opsForSet().members(IDS_KEY)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .subscribe(set -> ids = set, e -> log.warn("persistent route id refresh failed: {}", e.toString()));
    }

    private static PersistedRoute toPersisted(RouteDefinition route) {
        String path = route.predicates().stream()
                .filter(predicate -> predicate.kind() == RoutePredicate.Kind.PATH)
                .flatMap(predicate -> predicate.args().stream())
                .findFirst()
                .orElse(null);
        if (path == null) {
            return null;
        }
        return new PersistedRoute(route.id(), path, route.serviceId(), route.order(),
                route.auth().requiresToken(), route.traffic().rateLimited(),
                route.lifecycle().status().name(), route.lifecycle().sunset());
    }

    private static RouteDefinition toDefinition(PersistedRoute persisted) {
        AuthPolicy auth = persisted.authenticated()
                ? new AuthPolicy(true, Set.of(), null) : AuthPolicy.OPEN;
        TrafficPolicy traffic = persisted.rateLimited()
                ? new TrafficPolicy(null, null, true, false, 0, null) : TrafficPolicy.NONE;
        RouteLifecycle status = persisted.lifecycle() == null
                ? RouteLifecycle.PUBLISHED : RouteLifecycle.valueOf(persisted.lifecycle());
        return new RouteDefinition(persisted.id(), persisted.order(),
                List.of(new RoutePredicate(RoutePredicate.Kind.PATH, List.of(persisted.path()))),
                persisted.serviceId(), auth, traffic, TransformPolicy.NONE,
                new LifecyclePolicy(status, persisted.sunset()), Map.of());
    }
}
