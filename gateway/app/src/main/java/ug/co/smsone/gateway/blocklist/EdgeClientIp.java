package ug.co.smsone.gateway.blocklist;

import java.net.InetSocketAddress;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * The one place the edge decides which address a policy judges. X-Forwarded-For is client-writable,
 * so it is believed only when {@code trusted-proxy-hops} declares proxies in front — and then only
 * the Nth-from-right entry (the first our own proxy vouched for). Everything left of it is caller
 * input. Shared by the blocklist filter and the abuse guard so a block and the strike that caused it
 * always name the same source.
 */
@Component
public class EdgeClientIp {

    private static final int MAX_IP_LENGTH = 45; // longest IPv6 textual form

    private final int trustedHops;

    public EdgeClientIp(BlocklistProperties properties) {
        this.trustedHops = properties.trustedProxyHops();
    }

    /** @return the best-evidence client address, or null when it cannot be determined. */
    public String resolve(ServerWebExchange exchange) {
        if (trustedHops > 0) {
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] entries = forwarded.split(",");
                if (entries.length >= trustedHops) {
                    String candidate = entries[entries.length - trustedHops].trim();
                    if (!candidate.isEmpty() && candidate.length() <= MAX_IP_LENGTH) {
                        return candidate;
                    }
                }
            }
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
    }
}
