package ug.co.smsone.audit.internal;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.security.GatewaySecretVerifier;

/**
 * The API gateway's edge-audit seam — a MACHINE endpoint ({@code @Hidden}: it belongs in no tag),
 * permit-listed in {@code SecurityConfig} and authenticated here by the shared gateway secret
 * (constant-time compare). The gateway posts an edge decision it made (today: an access denial) and the
 * platform records it against its audit trail with the edge principal as the actor
 * ({@link AuditLog#recordExternal}) — the one place an audit actor arrives from another process. The
 * gateway's delivery is best-effort; recording here is durable, in a transaction.
 */
@Hidden
@RestController
@RequestMapping("/internal/gateway/audit")
class GatewayAuditController {

    private final AuditLog auditLog;
    private final GatewaySecretVerifier secretVerifier;

    GatewayAuditController(AuditLog auditLog, GatewaySecretVerifier secretVerifier) {
        this.auditLog = auditLog;
        this.secretVerifier = secretVerifier;
    }

    record EdgeAuditRequest(String action, String subject, String tenant, String method, String path,
            int status, String reason, String requestId, String traceId) {
    }

    @PostMapping
    @Transactional
    void record(@RequestHeader(name = "X-Gateway-Secret", required = false) String presentedSecret,
            @RequestBody EdgeAuditRequest request) {
        secretVerifier.verify(presentedSecret);
        auditLog.recordExternal(
                request.action() == null || request.action().isBlank() ? "gateway.event" : request.action(),
                parseOrg(request.tenant()),
                request.subject(),
                (request.method() + " " + request.path()).trim(),
                null,
                "status=" + request.status() + " reason=" + request.reason()
                        + " rid=" + request.requestId() + " trace=" + request.traceId());
    }

    /** A UUID tenant is org-scoped; a non-UUID tenant (a slug) or none is a platform-level row. */
    private static UUID parseOrg(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(tenant);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

}
