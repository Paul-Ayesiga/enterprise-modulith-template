package ug.co.smsone.audit.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared secret the API gateway presents when posting an edge audit event ({@code
 * app.gateway.audit-secret}); it must match the gateway's {@code gateway.platform.audit.secret}. Bound
 * separately from the introspection secret so the audit seam stays owned by the audit module.
 */
@ConfigurationProperties("app.gateway")
record GatewayAuditProperties(String auditSecret) {
}
