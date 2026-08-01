package ug.co.smsone.gateway.core.route;

import java.util.List;
import java.util.Optional;

/**
 * Port — resolves a route's {@code serviceId} to a backend. A static config adapter backs it today;
 * Kubernetes / Consul / Eureka / DNS discovery can back it later behind this same contract.
 */
public interface ServiceRegistry {

    Optional<ServiceDefinition> find(String serviceId);

    List<ServiceDefinition> all();
}
