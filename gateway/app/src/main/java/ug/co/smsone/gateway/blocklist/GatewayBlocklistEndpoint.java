package ug.co.smsone.gateway.blocklist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Runtime control of the edge IP controls on the admin port (token-gated like every admin endpoint).
 * The read shows every deny entry with its source — {@code config} (durable), {@code runtime}
 * (manual, until restart), {@code auto} (abuse-driven, with seconds-to-expiry) — plus the allow-set
 * and the live auto-block rules. One write shape: {@code {"cidr": "...", "blocked": true|false}} — a
 * bare IP normalizes to its host route; unblocking reaches both the manual set and the dynamic one,
 * so an operator can lift a false-positive auto-block early. Durable blocks belong in
 * {@code gateway.security.blocklist.cidrs}.
 */
@Component
@Endpoint(id = "gatewayblocklist")
public class GatewayBlocklistEndpoint {

    private final IpBlocklist blocklist;
    private final BlocklistProperties properties;
    private final ObjectProvider<AutoBlockStore> autoBlock;

    GatewayBlocklistEndpoint(IpBlocklist blocklist, BlocklistProperties properties,
            ObjectProvider<AutoBlockStore> autoBlock) {
        this.blocklist = blocklist;
        this.properties = properties;
        this.autoBlock = autoBlock;
    }

    @ReadOperation
    public Map<String, Object> blocklist() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (IpBlocklist.Entry entry : blocklist.entries()) {
            entries.add(Map.of("cidr", entry.cidr(), "source", entry.source()));
        }
        AutoBlockStore auto = autoBlock.getIfAvailable();
        if (auto != null) {
            auto.entries().forEach((ip, ttl) ->
                    entries.add(Map.of("cidr", ip, "source", "auto", "expiresInSeconds", ttl)));
        }
        BlocklistProperties.AutoBlock rules = properties.auto();
        Map<String, Object> autoRules = new LinkedHashMap<>();
        autoRules.put("enabled", auto != null);
        autoRules.put("window", rules.window().toString());
        autoRules.put("threshold", rules.threshold());
        autoRules.put("blockDuration", rules.blockDuration().toString());
        autoRules.put("statuses", rules.statuses());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", entries);
        body.put("count", entries.size());
        body.put("allow", blocklist.allowlist());
        body.put("trustedProxyHops", properties.trustedProxyHops());
        body.put("autoBlock", autoRules);
        return body;
    }

    @WriteOperation
    public Map<String, Object> update(String cidr, Boolean blocked) {
        boolean block = blocked == null || blocked;
        try {
            if (block) {
                String normalized = this.blocklist.block(cidr);
                return Map.of("cidr", normalized, "blocked", true, "source", "runtime");
            }
            // Lift from both the manual set and the dynamic one — an operator clearing a false
            // positive should not have to know which layer caught the source.
            boolean removedManual = this.blocklist.unblock(cidr);
            AutoBlockStore auto = autoBlock.getIfAvailable();
            boolean removedAuto = auto != null && auto.unblock(cidr.trim());
            return Map.of("cidr", cidr, "blocked", false, "removed", removedManual || removedAuto);
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }
}
