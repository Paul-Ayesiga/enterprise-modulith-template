package ug.co.smsone.gateway.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.audit.AuditSink;
import ug.co.smsone.gateway.core.audit.EdgeAuditEvent;

/**
 * Publishes edge audit events to the platform's audit endpoint over reactive HTTP, presenting the
 * shared gateway secret. The platform records them against its audit trail with the edge principal as
 * the actor. Best-effort by design: a failed publish is logged and swallowed, so an audit backlog or a
 * platform blip never turns into a failed edge request — the security log already holds the event.
 */
class ModulithAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger("gateway.error");

    private final WebClient webClient;
    private final String secret;

    ModulithAuditSink(String uri, String secret) {
        this.webClient = WebClient.create(uri);
        this.secret = secret;
    }

    @Override
    public Mono<Void> publish(EdgeAuditEvent event) {
        return webClient.post()
                .header("X-Gateway-Secret", secret)
                .bodyValue(event)
                .retrieve()
                .toBodilessEntity()
                .then()
                .onErrorResume(error -> {
                    log.warn("edge_audit_publish_failed action={} rid={} cause={}",
                            event.action(), event.requestId(), error.toString());
                    return Mono.empty();
                });
    }
}
