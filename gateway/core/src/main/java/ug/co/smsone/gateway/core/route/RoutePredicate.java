package ug.co.smsone.gateway.core.route;

import java.util.List;

/**
 * One matching condition on a route. {@link Kind} maps 1:1 to the runtime's predicate factories;
 * {@code args} are that factory's arguments (PATH → {@code ["/api/v1/**"]}, HEADER →
 * {@code ["X-Tenant", "acme"]}, METHOD → {@code ["GET", "POST"]}).
 */
public record RoutePredicate(Kind kind, List<String> args) {

    public enum Kind { PATH, HOST, HEADER, METHOD, QUERY }

    public RoutePredicate {
        args = args == null ? List.of() : List.copyOf(args);
    }
}
