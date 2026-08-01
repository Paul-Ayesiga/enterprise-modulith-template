/**
 * Compliance: consent (append-only history), legal holds, and GDPR erasure. Legal holds are the
 * load-bearing part — while a hold is active, the {@code LegalHolds} port answers "held" and BOTH
 * the soft-delete purge job and the erasure executor refuse to hard-delete that subject's or org's
 * data. Erasure orchestrates machinery that already exists (soft delete + the retention purge);
 * consent and holds are records that, like the audit trail, are never themselves quietly removable.
 * Privacy/portability: a self-service data export of the caller's own record.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Compliance")
package ug.co.smsone.compliance;
