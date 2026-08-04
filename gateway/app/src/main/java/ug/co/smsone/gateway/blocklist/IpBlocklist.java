package ug.co.smsone.gateway.blocklist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The active deny-set: YAML-seeded entries (durable — validated at boot, so a typo fails startup)
 * plus runtime entries from the admin endpoint (gone on restart; the endpoint says which is which).
 * Entries are normalized CIDRs; matching walks the set — a blocklist is short by nature, and an
 * entry count where a walk hurts is an upstream-firewall problem, not a gateway feature.
 */
@Component
@EnableConfigurationProperties(BlocklistProperties.class)
public class IpBlocklist {

    /** entry → true when it came from configuration (survives restart). */
    private final Map<String, Boolean> entries = new ConcurrentHashMap<>();

    public IpBlocklist(BlocklistProperties properties) {
        for (String cidr : properties.cidrs()) {
            entries.put(Cidrs.normalize(cidr), true);
        }
    }

    /** @return the first matching entry, or null when the address is not blocked. */
    public String matchedBy(String ip) {
        for (String cidr : entries.keySet()) {
            if (Cidrs.contains(cidr, ip)) {
                return cidr;
            }
        }
        return null;
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
