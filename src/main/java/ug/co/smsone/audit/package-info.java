/**
 * Audit module: an append-only trail of state changes across the system. It is a pure downstream
 * consumer — {@code @ApplicationModuleListener}s record an {@code audit_log} row for each domain event
 * other modules publish (idempotent via {@code EventInbox}, so at-least-once redelivery never
 * duplicates a row). Read-only REST: a platform-admin view over everything and an org-scoped view
 * gated by the {@code audit:read} permission. Internals live under {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit")
package ug.co.smsone.audit;
