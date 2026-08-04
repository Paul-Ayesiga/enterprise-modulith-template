package ug.co.smsone.gateway.blocklist;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The edge IP controls: an allow-set that is never blocked (trusted infra — health checkers, office,
 * internal), a durable deny-set, the proxy-trust declaration for reading the client address, and the
 * rules for DYNAMIC auto-blocking. {@code cidrs} and {@code allow} from YAML survive restarts;
 * runtime deny entries (admin endpoint) and auto-blocks (abuse-driven, TTL'd) do not — the read
 * shows which is which.
 *
 * <p>{@code trusted-proxy-hops}: 0 = the gateway is hit directly (X-Forwarded-For is client-writable
 * and ignored); 1 = behind one ingress that appends the client address; every IP decision reads the
 * Nth-from-right entry, never a spoofable left one.
 */
@ConfigurationProperties("gateway.security.blocklist")
public record BlocklistProperties(List<String> cidrs, List<String> allow, Integer trustedProxyHops,
        AutoBlock auto) {

    public BlocklistProperties {
        cidrs = cidrs == null ? List.of() : List.copyOf(cidrs);
        allow = allow == null ? List.of() : List.copyOf(allow);
        trustedProxyHops = trustedProxyHops == null ? 0 : trustedProxyHops;
        auto = auto == null ? new AutoBlock(null, null, null, null, null) : auto;
    }

    /**
     * The rules the platform tunes for fail2ban-style auto-blocking. Defaults are deliberately
     * conservative: a source that earns {@code threshold} denied responses (of the counted
     * {@code statuses}) inside {@code window} is blocked for {@code block-duration} — long enough to
     * shed a probing run, short enough that a shared-NAT false positive self-heals. Counting only
     * auth denials (401/403) by default keeps a merely-bursty client (429s) out of it; add 429/404
     * to widen.
     */
    public record AutoBlock(Boolean enabled, Duration window, Integer threshold,
            Duration blockDuration, List<Integer> statuses) {

        public AutoBlock {
            enabled = enabled != null && enabled;
            window = window == null ? Duration.ofMinutes(1) : window;
            threshold = threshold == null || threshold < 1 ? 20 : threshold;
            blockDuration = blockDuration == null ? Duration.ofMinutes(15) : blockDuration;
            statuses = statuses == null || statuses.isEmpty() ? List.of(401, 403) : List.copyOf(statuses);
        }
    }
}
