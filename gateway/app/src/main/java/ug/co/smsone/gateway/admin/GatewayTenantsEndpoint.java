package ug.co.smsone.gateway.admin;

import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.route.TenantUpstreams;

/**
 * The tenant-placement table the EDGE is actually using ({@code gatewaytenants}), on the management
 * port like every other admin surface — never the public one.
 *
 * <p>It exists because the failure this phase has to prevent is a silent one. A cutover moves a
 * tenant's rows to its own deployment and then points the edge at it; if the two disagree, the tenant
 * is either refused (loud, bounded) or — before this phase — served from a database that no longer
 * holds its rows (silent, and discovered as a support ticket). A read-only view of "which
 * organizations have their own upstream, and does that upstream resolve right now" is the cheapest
 * thing that turns the cutover's riskiest minute into something an operator can look at.
 *
 * <p>Read-only on purpose. Changing a tenant's placement from an HTTP call would make the routing
 * decision durable somewhere other than the reviewed configuration, and the whole argument in
 * {@code TenantUpstreamProperties} is that this fact belongs in configuration a deploy carries.
 */
@Component
@Endpoint(id = "gatewaytenants")
public class GatewayTenantsEndpoint {

    private final TenantUpstreams upstreams;

    public GatewayTenantsEndpoint(TenantUpstreams upstreams) {
        this.upstreams = upstreams;
    }

    @ReadOperation
    public Map<String, Map<String, Object>> tenants() {
        return upstreams.table();
    }
}
