package ug.co.smsone.gateway.core.route;

/**
 * Port — change the live service registry at runtime, no restart. A discovery source (or an admin API)
 * registers/removes a backend and the runtime re-reads it, so a route targeting a newly-registered
 * service starts resolving immediately. The config-seeded services are just the initial set.
 */
public interface ServiceRegistrar {

    /** Add the service, or replace an existing one with the same id. */
    void register(ServiceDefinition service);

    /** Drop the service with this id; a no-op if none matches. */
    void deregister(String serviceId);
}
