package ug.co.smsone.gateway.blocklist;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The durable half of the edge blocklist plus the proxy-trust declaration for reading the client
 * address. {@code cidrs} from YAML survive restarts; runtime additions via the
 * {@code gatewayblocklist} endpoint do not (same two-tier honesty as the route table).
 * {@code trusted-proxy-hops}: 0 = the gateway is hit directly (dev default — X-Forwarded-For is
 * client-writable and ignored); 1 = behind one ingress that appends the client address.
 */
@ConfigurationProperties("gateway.security.blocklist")
public record BlocklistProperties(List<String> cidrs, Integer trustedProxyHops) {

    public BlocklistProperties {
        cidrs = cidrs == null ? List.of() : List.copyOf(cidrs);
        trustedProxyHops = trustedProxyHops == null ? 0 : trustedProxyHops;
    }
}
