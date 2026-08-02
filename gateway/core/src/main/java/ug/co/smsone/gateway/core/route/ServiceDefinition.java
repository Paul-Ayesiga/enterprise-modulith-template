package ug.co.smsone.gateway.core.route;

import java.net.URI;
import java.util.List;

/**
 * A backend, kept separate from the routes that point at it (many routes → one service). A service has
 * one or more {@code instances}; with more than one the gateway load-balances across them (see
 * {@link #loadBalanced()}). {@code healthPath} is where the gateway checks a backend's liveness.
 */
public record ServiceDefinition(String id, List<URI> instances, String healthPath) {

    public ServiceDefinition {
        instances = instances == null ? List.of() : List.copyOf(instances);
        if (instances.isEmpty()) {
            throw new IllegalArgumentException("Service '" + id + "' must declare at least one instance");
        }
    }

    /** The primary instance — the direct target when the service is not load-balanced. */
    public URI uri() {
        return instances.get(0);
    }

    /** More than one instance means the edge balances across them (routed as {@code lb://id}). */
    public boolean loadBalanced() {
        return instances.size() > 1;
    }
}
