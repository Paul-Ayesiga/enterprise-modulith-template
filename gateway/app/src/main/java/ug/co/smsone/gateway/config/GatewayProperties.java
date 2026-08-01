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

    /** A route: which {@code predicates} match it, which {@code serviceId} it targets, its {@code auth}. */
    public record RouteProps(String id, int order, String serviceId, List<PredicateProps> predicates,
            AuthProps auth) {
        public RouteProps {
            predicates = predicates == null ? List.of() : predicates;
        }
    }

    /** A route's coarse edge auth policy: require a token, require scopes, enforce the path tenant. */
    public record AuthProps(boolean authenticated, List<String> scopes, String tenantPathTemplate) {
        public AuthProps {
            scopes = scopes == null ? List.of() : scopes;
        }
    }

    /** A predicate: its {@code kind} (PATH/HOST/HEADER/METHOD/QUERY) and the factory {@code args}. */
    public record PredicateProps(RoutePredicate.Kind kind, List<String> args) {
        public PredicateProps {
            args = args == null ? List.of() : args;
        }
    }
}
