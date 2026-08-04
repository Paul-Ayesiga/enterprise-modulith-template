package ug.co.smsone.gateway.blocklist;

import io.micrometer.core.instrument.MeterRegistry;
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
 * nothing downstream — no JWKS work, no quota reads, no backend). Three layers, checked in order:
 * the allow-set wins over everything (trusted infra is never refused); then the manual/config
 * deny-set (CIDRs); then the dynamic auto-block set ({@link AutoBlockStore}, present only when
 * auto-blocking is enabled). The judged address honors {@code trusted-proxy-hops} via
 * {@link EdgeClientIp}. Denials are counted by source-tier, security-logged, and audited.
 */
@Component
class IpBlocklistFilter implements GlobalFilter, Ordered {

    private static final Logger securityLog = LoggerFactory.getLogger("gateway.security");

    private final IpBlocklist blocklist;
    private final EdgeClientIp clientIp;
    private final ObjectProvider<AutoBlockStore> autoBlock;
    private final MeterRegistry meterRegistry;
    private final AuditSink auditSink;

    IpBlocklistFilter(IpBlocklist blocklist, EdgeClientIp clientIp, ObjectProvider<AutoBlockStore> autoBlock,
            ObjectProvider<MeterRegistry> meterRegistry, ObjectProvider<AuditSink> auditSink) {
        this.blocklist = blocklist;
        this.clientIp = clientIp;
        this.autoBlock = autoBlock;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.auditSink = auditSink.getIfAvailable();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String ip = clientIp.resolve(exchange);
        if (ip == null || blocklist.isAllowed(ip)) {
            return chain.filter(exchange); // unknown source or trusted infra: never refused here
        }
        String matched = blocklist.matchedBy(ip);
        String source = matched != null ? blocklist.sourceOf(matched) : null;
        if (matched == null) {
            AutoBlockStore auto = autoBlock.getIfAvailable();
            if (auto != null && auto.isBlocked(ip)) {
                matched = ip;
                source = "auto";
            }
        }
        if (matched == null) {
            return chain.filter(exchange);
        }
        String method = String.valueOf(exchange.getRequest().getMethod());
        String path = exchange.getRequest().getURI().getRawPath();
        String requestId = GatewayAttributes.requestId(exchange);
        if (meterRegistry != null) {
            // Tag by source tier (config/runtime/auto) — auto entries are per-IP, so tagging the CIDR
            // would explode metric cardinality.
            meterRegistry.counter("gateway.blocklist.denied", "source", source).increment();
        }
        securityLog.warn("edge_ip_blocked ip={} source={} rule={} method={} path={} rid={}",
                ip, source, matched, method, path, requestId);
        if (auditSink != null) {
            auditSink.publish(new EdgeAuditEvent("gateway.ip_blocked", null, null, method, path,
                    HttpStatus.FORBIDDEN.value(), "ip_blocklist " + source + " " + matched, requestId,
                    GatewayAttributes.traceId(exchange))).subscribe();
        }
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Source address blocked"));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3; // after request-id(+0)/trace(+1)/access-log(+2), before abuse-guard(+4)/auth(+10)
    }
}
