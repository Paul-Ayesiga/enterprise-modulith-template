package ug.co.smsone.gateway.core.route;

import java.net.URI;

/**
 * A backend, kept separate from the routes that point at it (many routes → one service).
 * {@code healthPath} is where the gateway checks the backend's liveness.
 */
public record ServiceDefinition(String id, URI uri, String healthPath) {
}
