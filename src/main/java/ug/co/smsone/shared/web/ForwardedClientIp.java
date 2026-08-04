package ug.co.smsone.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * The client address a policy decision may act on. {@code X-Forwarded-For} is client-writable, so
 * it is never believed by default: {@code app.http.trusted-proxy-hops} declares how many of OUR
 * proxies stand in front (each appending exactly one entry), and only then is the Nth-from-right
 * entry — the first one a proxy of ours vouched for — taken. Everything left of it is whatever the
 * caller typed. Deployment values: 0 hit directly (dev default), 1 behind the gateway, 2 behind
 * ingress → gateway (the Helm chart's shape).
 *
 * <p>Fewer entries than declared hops means the request did NOT traverse our proxy line (an
 * in-cluster caller hitting the pod directly) — the socket peer is then the honest answer, never
 * a partial-trust read of the header.
 */
@Component
public class ForwardedClientIp {

    private static final int MAX_IP_LENGTH = 45; // longest IPv6 textual form

    private final int trustedHops;

    public ForwardedClientIp(HttpForwardingProperties properties) {
        this.trustedHops = properties.trustedProxyHops() == null ? 0 : properties.trustedProxyHops();
    }

    /** Declared proxy depth — 0 means "trust nothing but the socket". */
    public int trustedHops() {
        return trustedHops;
    }

    /** The best-evidence client address under the configured trust. Never null. */
    public String clientIp(HttpServletRequest request) {
        if (trustedHops > 0) {
            String forwarded = request.getHeader("X-Forwarded-For");
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
        return request.getRemoteAddr();
    }

    @ConfigurationProperties(prefix = "app.http")
    public record HttpForwardingProperties(Integer trustedProxyHops) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HttpForwardingProperties.class)
    static class ForwardingConfiguration {
    }
}
