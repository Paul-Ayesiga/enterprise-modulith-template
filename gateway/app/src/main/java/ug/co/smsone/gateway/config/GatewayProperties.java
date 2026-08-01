package ug.co.smsone.gateway.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ug.co.smsone.gateway.core.route.RoutePredicate;

/**
 * The static route/service catalog as configuration ({@code gateway.*}). This is the config-driven
 * source the {@code RouteSource}/{@code ServiceRegistry} ports read from today; an admin store or a
 * discovery integration can replace it later without the core or the runtime changing.
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(List<ServiceProps> services, List<RouteProps> routes) {

    public GatewayProperties {
        services = services == null ? List.of() : services;
        routes = routes == null ? List.of() : routes;
    }

    /** A backend: {@code id}, its base {@code uri}, and where to health-check it. */
    public record ServiceProps(String id, URI uri, String healthPath) {
    }

    /** A route: which {@code predicates} match it and which {@code serviceId} it targets. */
    public record RouteProps(String id, int order, String serviceId, List<PredicateProps> predicates) {
        public RouteProps {
            predicates = predicates == null ? List.of() : predicates;
        }
    }

    /** A predicate: its {@code kind} (PATH/HOST/HEADER/METHOD/QUERY) and the factory {@code args}. */
    public record PredicateProps(RoutePredicate.Kind kind, List<String> args) {
        public PredicateProps {
            args = args == null ? List.of() : args;
        }
    }
}
