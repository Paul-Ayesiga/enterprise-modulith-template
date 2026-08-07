package ug.co.smsone.identity.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;

/**
 * Editing a person's name — the one write on {@code person}'s seven name columns, whether the person
 * is correcting their own or an operator is fixing a typo in someone else's.
 *
 * <p>One service for both callers on purpose. The two differ only in who is allowed to ask (that is
 * the controllers' business, and the two axes answer it differently); what a name IS, what a patch
 * means, and what the trail must say about it are the same question, and answering it twice is how a
 * self-edit and an admin correction end up obeying different rules about {@code formatted_name}.
 *
 * <p><b>{@code formattedName} is never derived here.</b> It changes when and only when the caller
 * sends it. Composing it from the parts — even "just as a fallback when it is null" — prints many
 * people's names backwards, because given-then-family is one convention among several. V10 and
 * {@link PersonName} both state this; this class is where it would be most tempting to break it.
 */
@Service
class PersonNameService {

    /**
     * One action for both surfaces. {@code audit_log.actor_person_id} already answers "was this a
     * self-edit or a correction?" — it equals {@code target} for the former and not for the latter —
     * and inside an impersonation session it names the OPERATOR with the worn identity in
     * {@code on_behalf_of_person_id} (§5.5). A second action name would be a second spelling of a fact
     * the row already carries, and the two would drift.
     */
    private static final String NAME_CHANGED = "identity.person_name_changed";

    private final PersonRepository persons;
    private final AuditLog auditLog;

    PersonNameService(PersonRepository persons, AuditLog auditLog) {
        this.persons = persons;
        this.auditLog = auditLog;
    }

    /**
     * Applies {@code patch} and returns the name as it now stands.
     *
     * <p>Transactional so the {@code person} row and the audit row commit together: a name change with
     * no record of who made it is the one outcome this endpoint must not be able to produce.
     */
    @Transactional
    PersonName apply(UUID personId, NamePatch patch) {
        Person person = require(personId);
        PersonName current = person.getName();
        PersonName updated = patch.applyTo(current);
        // Idempotent change → return early (AGENTS §6). Writing anyway would bump `version` and
        // `updated_by` and file an audit row claiming a change that did not happen — and a client
        // re-sending the name it already read is the ordinary case, not an odd one.
        if (updated.equals(current)) {
            return current;
        }
        person.rename(updated);
        persons.save(person);
        // orgId is null: a person belongs to the platform, not to a tenant — they may be a member of
        // several — so this lands in the platform trail rather than in one org's audit feed. Same
        // reasoning as the impersonation lifecycle rows.
        auditLog.record(NAME_CHANGED, null, personId.toString(), patch.render(current), patch.render(updated));
        return updated;
    }

    /**
     * {@code @SQLRestriction} hides a soft-deleted person from this query, so an erased account and one
     * that never existed are the same 404 here — deliberately: to an operator correcting a name, an
     * account that has been erased is gone, and saying "it exists but you may not touch it" would only
     * invite them to re-create it.
     */
    private Person require(UUID personId) {
        return persons.findById(personId)
                .orElseThrow(() -> new NotFoundException("No such user."));
    }
}
