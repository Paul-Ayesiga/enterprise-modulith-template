package ug.co.smsone.gateway.blocklist;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Runtime control of the edge deny-list on the admin port (token-gated like every admin endpoint).
 * One write shape: {@code {"cidr": "...", "blocked": true|false}} — a bare IP normalizes to its
 * host route. Runtime entries do not survive a restart; durable blocks belong in
 * {@code gateway.security.blocklist.cidrs} (the read shows which is which).
 */
@Component
@Endpoint(id = "gatewayblocklist")
public class GatewayBlocklistEndpoint {

    private final IpBlocklist blocklist;
    private final BlocklistProperties properties;

    GatewayBlocklistEndpoint(IpBlocklist blocklist, BlocklistProperties properties) {
        this.blocklist = blocklist;
        this.properties = properties;
    }

    @ReadOperation
    public Map<String, Object> blocklist() {
        List<IpBlocklist.Entry> entries = blocklist.entries();
        return Map.of(
                "entries", entries,
                "count", entries.size(),
                "trustedProxyHops", properties.trustedProxyHops());
    }

    @WriteOperation
    public Map<String, Object> update(String cidr, Boolean blocked) {
        boolean block = blocked == null || blocked;
        try {
            if (block) {
                String normalized = this.blocklist.block(cidr);
                return Map.of("cidr", normalized, "blocked", true, "source", "runtime");
            }
            boolean removed = this.blocklist.unblock(cidr);
            return Map.of("cidr", cidr, "blocked", false, "removed", removed);
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }
}
