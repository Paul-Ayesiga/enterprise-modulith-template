package ug.co.smsone.gateway.core.route;

import java.util.List;

/**
 * Port — a discovery source that knows some set of backends: Kubernetes, Consul, Eureka, Nacos, DNS, …
 * The platform contributes one adapter per integration; the gateway polls them and registers what they
 * report (see the runtime's discovery registrar) so a backend appears automatically, without config.
 * With no {@code ServiceResolver} on the context, the gateway simply runs on its configured services.
 */
public interface ServiceResolver {

    /** The services this source currently knows about. */
    List<ServiceDefinition> discover();
}
