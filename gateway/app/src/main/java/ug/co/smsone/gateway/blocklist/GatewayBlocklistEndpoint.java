package ug.co.smsone.gateway.blocklist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Runtime control of the edge IP controls on the admin port (token-gated like every admin endpoint).
 * The read shows every deny entry with its source — {@code config} (durable, YAML), {@code runtime}
 * (manual, until restart), {@code persistent} (manual, survives restart via Valkey), {@code auto}
 * (abuse-driven, with seconds-to-expiry) — plus the allow-set and the live auto-block rules. Write:
 * {@code {"cidr": "...", "blocked": true|false, "persist": true|false}} — a bare IP normalizes to its
 * host route; {@code persist:true} makes the block durable; unblocking reaches ALL tiers, so an
 * operator clearing a source need not know which layer caught it.
 */
@Component
@Endpoint(id = "gatewayblocklist")
public class GatewayBlocklistEndpoint {

    private final IpBlocklist blocklist;
    private final BlocklistProperties properties;
    private final ObjectProvider<PersistentBlocklist> persistentBlocklist;
    private final ObjectProvider<AutoBlockStore> autoBlock;

    GatewayBlocklistEndpoint(IpBlocklist blocklist, BlocklistProperties properties,
            ObjectProvider<PersistentBlocklist> persistentBlocklist, ObjectProvider<AutoBlockStore> autoBlock) {
        this.blocklist = blocklist;
        this.properties = properties;
        this.persistentBlocklist = persistentBlocklist;
        this.autoBlock = autoBlock;
    }

    @ReadOperation
    public Map<String, Object> blocklist() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (IpBlocklist.Entry entry : blocklist.entries()) {
            entries.add(Map.of("cidr", entry.cidr(), "source", entry.source()));
        }
        PersistentBlocklist persistent = persistentBlocklist.getIfAvailable();
        if (persistent != null) {
            for (String cidr : persistent.entries()) {
                entries.add(Map.of("cidr", cidr, "source", "persistent"));
            }
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
        body.put("persistentEnabled", persistent != null);
        body.put("autoBlock", autoRules);
        return body;
    }

    @WriteOperation
    public Map<String, Object> update(String cidr, @Nullable Boolean blocked, @Nullable Boolean persist) {
        boolean block = blocked == null || blocked;
        boolean durable = Boolean.TRUE.equals(persist);
        try {
            if (block) {
                PersistentBlocklist persistent = persistentBlocklist.getIfAvailable();
                if (durable && persistent != null) {
                    String normalized = persistent.add(cidr);
                    return Map.of("cidr", normalized, "blocked", true, "source", "persistent");
                }
                // Durable requested but the persistent store is off → fall back to runtime, and say so.
                String normalized = this.blocklist.block(cidr);
                return durable
                        ? Map.of("cidr", normalized, "blocked", true, "source", "runtime",
                                "warning", "Persistent blocking is disabled; added as a runtime block (until restart).")
                        : Map.of("cidr", normalized, "blocked", true, "source", "runtime");
            }
            // Lift from every tier — the operator should not have to know which layer caught the source.
            boolean removedManual = this.blocklist.unblock(cidr);
            PersistentBlocklist persistent = persistentBlocklist.getIfAvailable();
            boolean removedPersistent = persistent != null && persistent.remove(cidr);
            AutoBlockStore auto = autoBlock.getIfAvailable();
            boolean removedAuto = auto != null && auto.unblock(cidr.trim());
            return Map.of("cidr", cidr, "blocked", false,
                    "removed", removedManual || removedPersistent || removedAuto);
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }
}
