package ug.co.smsone.gateway.core.route;

/**
 * Port — change the live route table at runtime, no restart. {@link #register} adds or replaces a route
 * by id; {@link #remove} drops one. The runtime re-reads the {@link RouteSource} and the edge re-applies
 * policies after each change, so a route (and its auth/traffic/transform) takes effect immediately. An
 * admin API (or a config watcher) drives this; the config-seeded routes are just the initial table.
 */
public interface RouteRegistrar {

    /** Add the route, or replace an existing one with the same id. */
    void register(RouteDefinition route);

    /** Remove the route with this id; a no-op if none matches. */
    void remove(String routeId);
}
