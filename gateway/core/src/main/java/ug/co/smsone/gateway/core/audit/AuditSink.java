package ug.co.smsone.gateway.core.audit;

import reactor.core.publisher.Mono;

/**
 * Port — publish an edge audit event to the platform's audit trail. The platform supplies the
 * implementation (an adapter that calls its audit store); the core knows only the contract. When no
 * adapter is on the context, edge auditing is simply off. Publishing is best-effort and must never
 * block or fail the request that produced the event — the local security log is the durable record.
 */
public interface AuditSink {

    Mono<Void> publish(EdgeAuditEvent event);
}
