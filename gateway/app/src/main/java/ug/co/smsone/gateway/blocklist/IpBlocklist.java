package ug.co.smsone.gateway.blocklist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The manual/config deny-set and the allow-set. YAML-seeded entries are durable (validated at boot,
 * so a typo fails startup); runtime deny entries from the admin endpoint are gone on restart (the
 * endpoint says which is which). The allow-set is the safety valve for the dynamic layer: an
 * allowlisted source is never auto-blocked and never accrues abuse strikes, so a health checker or
 * office range can't be locked out by its own bad minute. Matching walks the sets — an IP control
 * list is short by nature; a length where a walk hurts is an upstream-firewall job.
 */
@Component
@EnableConfigurationProperties(BlocklistProperties.class)
public class IpBlocklist {

    /** entry → true when it came from configuration (survives restart). */
    private final Map<String, Boolean> entries = new ConcurrentHashMap<>();
    private final List<String> allow;

    public IpBlocklist(BlocklistProperties properties) {
        for (String cidr : properties.cidrs()) {
            entries.put(Cidrs.normalize(cidr), true);
        }
        this.allow = properties.allow().stream().map(Cidrs::normalize).toList();
    }

    /** True when the source is on the allow-set — it wins over every block, manual or dynamic. */
    public boolean isAllowed(String ip) {
        for (String cidr : allow) {
            if (Cidrs.contains(cidr, ip)) {
                return true;
            }
        }
        return false;
    }

    public List<String> allowlist() {
        return allow;
    }

    /** @return the first matching manual/config entry, or null. Auto-blocks live in AutoBlockStore. */
    public String matchedBy(String ip) {
        for (String cidr : entries.keySet()) {
            if (Cidrs.contains(cidr, ip)) {
                return cidr;
            }
        }
        return null;
    }

    /** The source of a matched manual entry — "config" (durable) or "runtime". */
    public String sourceOf(String cidr) {
        Boolean fromConfig = entries.get(cidr);
        return fromConfig == null ? "runtime" : (fromConfig ? "config" : "runtime");
    }

    /** @return the normalized entry. Throws IllegalArgumentException on junk — the caller 400s. */
    public String block(String cidr) {
        String normalized = Cidrs.normalize(cidr);
        entries.putIfAbsent(normalized, false);
        return normalized;
    }

    /** @return true when something was removed. Removing a config entry lasts until restart. */
    public boolean unblock(String cidr) {
        return entries.remove(Cidrs.normalize(cidr)) != null;
    }

    public List<Entry> entries() {
        List<Entry> view = new ArrayList<>();
        entries.forEach((cidr, fromConfig) -> view.add(new Entry(cidr, fromConfig ? "config" : "runtime")));
        view.sort(java.util.Comparator.comparing(Entry::cidr));
        return view;
    }

    public record Entry(String cidr, String source) {
    }
}
