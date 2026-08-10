package ug.co.smsone.gateway.route;

import java.net.URI;
import ug.co.smsone.gateway.core.route.ServiceDefinition;

/**
 * How a registered service is ADDRESSED by the runtime — one instance goes straight to its uri, several
 * are reached as {@code lb://<id>} so Spring Cloud LoadBalancer picks one per request (and the
 * health-checked supplier ejects a dead one).
 *
 * <p>It is a class rather than a line in each caller because there are now two of them and they must
 * never disagree: {@link GatewayRouteLocator} addresses a route's configured service at build time, and
 * {@link TenantUpstreamFilter} addresses a tenant's dedicated service per request. If the second used
 * the plain uri while the first used {@code lb://}, a multi-instance tenant deployment would quietly
 * lose its balancing and its failover, and every request would pin to whichever instance happens to be
 * first in the config list.
 */
final class ServiceAddress {

    private ServiceAddress() {
    }

    static URI of(ServiceDefinition service) {
        return service.loadBalanced() ? URI.create("lb://" + service.id()) : service.uri();
    }
}
