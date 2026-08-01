package ug.co.smsone.gateway.core.route;

import java.util.List;
import java.util.Map;
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.core.traffic.TrafficPolicy;

/**
 * A route as CONFIGURATION, not code: the predicates that match it, the service it targets, its
 * priority, its coarse {@link AuthPolicy}, its {@link TrafficPolicy}, and free-form metadata. The
 * runtime (gateway-app) translates this into its own route table; the core never depends on that
 * runtime, so the same definition could drive a different one.
 */
public record RouteDefinition(
        String id,
        int order,
        List<RoutePredicate> predicates,
        String serviceId,
        AuthPolicy auth,
        TrafficPolicy traffic,
        Map<String, String> metadata) {

    public RouteDefinition {
        predicates = predicates == null ? List.of() : List.copyOf(predicates);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        auth = auth == null ? AuthPolicy.OPEN : auth;
        traffic = traffic == null ? TrafficPolicy.NONE : traffic;
    }
}
