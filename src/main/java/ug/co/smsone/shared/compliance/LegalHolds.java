package ug.co.smsone.shared.compliance;

import java.util.UUID;

/**
 * Kernel port (the {@code AuditLog} pattern): "is this person under an active legal hold?"
 * The port lives in {@code shared} so a guard outside {@code compliance} can ask the question without
 * compile-depending on the module that owns {@code legal_hold}. Consumers inject it through an
 * {@code ObjectProvider} and treat an absent implementation as "no hold" — fail-open on purpose, so a
 * missing compliance module cannot freeze every deletion on the platform.
 *
 * <p>It takes a {@code person.id} because {@code legal_hold.person_id} is a uuid. A String overload
 * could only be honoured by parsing one, and a hold that silently matches nothing is the exact failure
 * V34 warns about: it does not throw, it just resumes deleting data a court said to keep.
 *
 * <p>There is deliberately NO org half. Org-scoped holds ARE enforced — but by
 * {@code SoftDeletePurgeJob.heldGuard()}, which folds {@code not exists (select 1 from
 * platform.legal_hold … where h.org_id = <table>.<col>)} straight into each purge statement so the guard is part of the
 * DELETE rather than a separate round trip per row. A Java-side {@code orgHeld(UUID)} would be a
 * second, weaker way to ask the same question, and every caller that needs it is the purge job.
 */
public interface LegalHolds {

    boolean personHeld(UUID personId);
}
