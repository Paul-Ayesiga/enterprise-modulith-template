/**
 * Audit module: an append-only trail of state changes across the system.
 *
 * <p>Recording is <b>synchronous</b>, through the shared {@code AuditLog} port, at each mutation and
 * inside the transaction that performs it — so the row and the change it describes commit together or
 * not at all. This module consumes no domain events and registers no
 * {@code @ApplicationModuleListener}: an async consumer would let a change commit while the row
 * explaining it was still in flight, and a trail that can lag its subject is not a trail.
 *
 * <p>Read-only REST: a {@code platform-support} view over everything, and an org-scoped view gated by
 * the {@code audit:read} permission. Internals live under {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit")
package ug.co.smsone.audit;
