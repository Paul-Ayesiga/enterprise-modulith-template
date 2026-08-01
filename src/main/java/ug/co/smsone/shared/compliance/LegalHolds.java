package ug.co.smsone.shared.compliance;

import java.util.UUID;

/**
 * Kernel port (the {@code AuditLog} pattern): "is this subject or org under an active legal hold?"
 * The scheduler's purge job lives in {@code scheduler} and must consult holds owned by
 * {@code compliance} without depending on it — so the port lives in {@code shared} and
 * default-answers FALSE (no hold) when no implementation is present, which is fail-open on purpose:
 * a missing compliance module must not freeze all purging.
 */
public interface LegalHolds {

    boolean subjectHeld(String subject);

    boolean orgHeld(UUID organizationId);
}
