package ug.co.smsone.gateway.blocklist;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.audit.AuditSink;
import ug.co.smsone.gateway.core.audit.EdgeAuditEvent;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * The platform's front-door deny-list, earliest policy in the chain (+3: after request-id/trace/
 * access-log so a denial is attributable, before lifecycle +5 and auth +10 so hostile traffic costs
 * nothing downstream — no JWKS work, no quota reads, no backend). The judged address is the socket
 * peer unless {@code trusted-proxy-hops} declares an ingress in front; X-Forwarded-For is never
 * believed otherwise. Denials are counted, security-logged, and audited like auth denials.
 */
@Component
class IpBlocklistFilter implements GlobalFilter, Ordered {

    private static final Logger securityLog = LoggerFactory.getLogger("gateway.security");
    private static final int MAX_IP_LENGTH = 45;

    private final IpBlocklist blocklist;
    private final int trustedHops;
    private final MeterRegistry meterRegistry;
    private final AuditSink auditSink;

    IpBlocklistFilter(IpBlocklist blocklist, BlocklistProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry, ObjectProvider<AuditSink> auditSink) {
        this.blocklist = blocklist;
        this.trustedHops = properties.trustedProxyHops();
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.auditSink = auditSink.getIfAvailable();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String ip = clientIp(exchange);
        String rule = ip == null ? null : blocklist.matchedBy(ip);
        if (rule == null) {
            return chain.filter(exchange);
        }
        String method = String.valueOf(exchange.getRequest().getMethod());
        String path = exchange.getRequest().getURI().getRawPath();
        String requestId = GatewayAttributes.requestId(exchange);
        if (meterRegistry != null) {
            meterRegistry.counter("gateway.blocklist.denied", "rule", rule).increment();
        }
        securityLog.warn("edge_ip_blocked ip={} rule={} method={} path={} rid={}",
                ip, rule, method, path, requestId);
        if (auditSink != null) {
            auditSink.publish(new EdgeAuditEvent("gateway.ip_blocked", null, null, method, path,
                    HttpStatus.FORBIDDEN.value(), "ip_blocklist " + rule, requestId,
                    GatewayAttributes.traceId(exchange))).subscribe();
        }
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Source address blocked"));
    }

    /** Same trust rule as the platform's app.http.trusted-proxy-hops, evaluated at the edge. */
    private String clientIp(ServerWebExchange exchange) {
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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3; // after request-id(+0)/trace(+1)/access-log(+2), before lifecycle(+5)/auth(+10)
    }
}
