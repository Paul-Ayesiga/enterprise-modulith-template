package ug.co.smsone.gateway.blocklist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.core.audit.AuditSink;
import ug.co.smsone.gateway.core.audit.EdgeAuditEvent;

/**
 * The DURABLE operator deny-list: manual blocks that SURVIVE a gateway restart, backed by a Valkey
 * set ({@code gwblock:persistent}). Config CIDRs are baked into YAML at boot; this is how a block
 * added live from the admin UI is made permanent without a redeploy. Hydrated on startup and
 * refreshed across instances every few seconds, so a block one instance persists is honored by all;
 * hot-path reads hit the in-memory snapshot (no Valkey per request). Best-effort against Valkey — a
 * blip logs and the last snapshot stands. Present only when
 * {@code gateway.security.blocklist.persistent.enabled} is not turned off.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.security.blocklist.persistent", name = "enabled",
        matchIfMissing = true, havingValue = "true")
public class PersistentBlocklist {

    private static final Logger securityLog = LoggerFactory.getLogger("gateway.security");
    private static final String SKEY = "gwblock:persistent";

    private final ReactiveStringRedisTemplate redis;
    private final AuditSink auditSink;
    private volatile Set<String> snapshot = Set.of();

    PersistentBlocklist(ReactiveStringRedisTemplate redis, ObjectProvider<AuditSink> auditSink) {
        this.redis = redis;
        this.auditSink = auditSink.getIfAvailable();
        refresh(); // best-effort hydrate at startup so durable blocks apply from the first request
    }

    /** Hot-path read: is this source covered by a durable operator block? Pure in-memory. */
    public boolean contains(String ip) {
        for (String cidr : snapshot) {
            if (Cidrs.contains(cidr, ip)) {
                return true;
            }
        }
        return false;
    }

    public List<String> entries() {
        return new ArrayList<>(snapshot);
    }

    /** @return the normalized CIDR. Throws IllegalArgumentException on junk — the caller 400s. */
    public String add(String cidr) {
        String normalized = Cidrs.normalize(cidr);
        Set<String> next = new LinkedHashSet<>(snapshot);
        next.add(normalized);
        snapshot = next; // immediate local effect; Valkey write persists it across restarts + peers
        redis.opsForSet().add(SKEY, normalized).subscribe(n -> { }, e ->
                securityLog.warn("persistent blocklist add to Valkey failed (local until refresh): {}", e.toString()));
        securityLog.warn("edge_ip_blocked_persistent cidr={}", normalized);
        if (auditSink != null) {
            auditSink.publish(new EdgeAuditEvent("gateway.ip_blocked_persistent", null, null, null, null,
                    403, "persistent " + normalized, null, null)).subscribe();
        }
        return normalized;
    }

    public boolean remove(String cidr) {
        String normalized = Cidrs.normalize(cidr);
        boolean had = snapshot.contains(normalized);
        if (had) {
            Set<String> next = new LinkedHashSet<>(snapshot);
            next.remove(normalized);
            snapshot = next;
        }
        redis.opsForSet().remove(SKEY, normalized).subscribe(n -> { }, e ->
                securityLog.warn("persistent blocklist remove from Valkey failed: {}", e.toString()));
        return had;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    void refresh() {
        redis.opsForSet().members(SKEY)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .subscribe(set -> snapshot = set, e ->
                        securityLog.warn("persistent blocklist refresh failed: {}", e.toString()));
    }
}
