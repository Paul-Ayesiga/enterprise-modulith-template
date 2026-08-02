package ug.co.smsone.gateway.core.audit;

/**
 * An audited edge decision — the who/what/where/outcome of something the gateway itself decided (today:
 * an access denial). It carries its own {@code subject} (the edge principal the gateway resolved) and
 * {@code tenant}, because the platform records the event outside any user's request context: the actor
 * genuinely lives in another process. {@code requestId}/{@code traceId} tie it back to the request.
 */
public record EdgeAuditEvent(String action, String subject, String tenant, String method, String path,
        int status, String reason, String requestId, String traceId) {
}
