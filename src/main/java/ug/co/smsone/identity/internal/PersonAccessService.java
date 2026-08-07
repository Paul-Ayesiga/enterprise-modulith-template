package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * The access decision for an authenticated human, with lazy {@code INVITED → ACTIVE} activation — plus
 * the module's read-only {@code person} queries, so no controller touches a repository (§3.1).
 *
 * <p><b>Every entry point takes a {@code person.id}</b> — the edge resolves it now, so the resolve step
 * this class used to perform is gone. The one exception is {@link #explainAbsence(String)}, which is
 * handed a raw subject precisely because there is no person to name: only this module can say whether an
 * unresolvable subject was never linked or was erased, and that distinction is a provisioning policy, not
 * an edge concern.
 */
@Service
class PersonAccessService {

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    enum Decision {
        ALLOWED,
        NOT_PROVISIONED,
        DISABLED
    }

    /** One row of the platform listing: identity, the label to show, and how to reach them. */
    record PersonSummary(UUID personId, String formattedName, String email, ProvisioningStatus status) {
    }

    /**
     * What {@code /me} needs about the caller's own account. {@code personId} and {@code email} are both
     * null before provisioning — there is no person yet, so there is nothing to reach them at either.
     */
    record SelfView(UUID personId, String email, String provisioningStatus) {
    }

    private final PersonRepository persons;
    private final PersonResolver resolver;
    private final PersonContacts contacts;
    private final Clock clock;

    PersonAccessService(PersonRepository persons, PersonResolver resolver, PersonContacts contacts, Clock clock) {
        this.persons = persons;
        this.resolver = resolver;
        this.contacts = contacts;
        this.clock = clock;
    }

    /**
     * The gate's decision, in ONE column-scoped read on the overwhelmingly common path.
     *
     * <p>The aggregate is loaded only for the {@code INVITED} branch — once in an account's life —
     * because that branch is the only one that writes. An {@code ACTIVE} or {@code DISABLED} caller
     * previously hydrated a whole {@code person} on every request to look at one enum.
     *
     * <p>Not {@code @Transactional}: {@code save()} runs in its own transaction so a concurrent-
     * activation optimistic lock failure surfaces HERE (catchable), not at an outer commit the catch
     * could never reach.
     */
    Decision authorize(UUID personId) {
        ProvisioningStatus status = persons.statusById(personId).orElse(null);
        if (status == null) {
            // The edge resolved this id from a LIVE external_identity row, whose FK guarantees the person
            // exists — so an absence here can only be the soft delete that @SQLRestriction is hiding.
            return Decision.DISABLED;
        }
        return switch (status) {
            case DISABLED -> Decision.DISABLED;
            case INVITED -> activate(personId);
            case ACTIVE -> Decision.ALLOWED;
        };
    }

    /**
     * Flips INVITED → ACTIVE. Re-reads the row because the status projection above is not a lock: the
     * entity is what carries the version and the {@code PersonActivated} event, and
     * {@link Person#activate} is a no-op on a row another request already flipped.
     */
    private Decision activate(UUID personId) {
        try {
            persons.findById(personId).ifPresent(person -> {
                person.activate(clock.instant()); // publishes PersonActivated
                persons.save(person);
            });
        } catch (OptimisticLockingFailureException ex) {
            // A parallel first request (typical SPA page load) won the activation race — the row is
            // ACTIVE either way, so this request is allowed, not a 500.
        }
        return Decision.ALLOWED;
    }

    /** Status peek WITHOUT lazy activation — for endpoints exempt from the gate (onboarding {@code /me}). */
    Decision peek(UUID personId) {
        // Same reasoning as authorize(): resolved-but-absent means deleted.
        return persons.statusById(personId)
                .map(status -> status == ProvisioningStatus.DISABLED ? Decision.DISABLED : Decision.ALLOWED)
                .orElse(Decision.DISABLED);
    }

    /** The {@code /me} surface — never activates (that is {@link #authorize}'s job). */
    @Transactional(readOnly = true)
    SelfView selfView(UUID personId) {
        if (personId == null) {
            return new SelfView(null, null, "UNPROVISIONED");
        }
        return persons.findById(personId)
                .map(person -> new SelfView(person.getId(), contacts.emailOf(person.getId()).orElse(null),
                        person.getStatus().name()))
                .orElseGet(() -> new SelfView(null, null, "UNPROVISIONED"));
    }

    /**
     * The platform listing. The page's e-mails are fetched in ONE follow-up query rather than per row,
     * and {@link Window#map} is safe to use for the projection because the keyset positions were
     * computed from the entities before the mapping.
     */
    @Transactional(readOnly = true)
    Window<PersonSummary> list(CursorPageRequest page) {
        Window<Person> window = persons.findBy((root, query, cb) -> cb.conjunction(),
                query -> query.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
        List<UUID> ids = window.stream().map(Person::getId).toList();
        Map<UUID, String> emails = contacts.emailsByPersonIds(ids);
        return window.map(person -> new PersonSummary(person.getId(), person.getFormattedName(),
                emails.get(person.getId()), person.getStatus()));
    }

    /**
     * The edge resolved no person. Two situations arrive as the same absence and must not decide the
     * same way, which is the entire reason this method takes a subject and the entire reason the edge
     * does not answer it:
     *
     * <ul>
     *   <li>the subject was never linked — NOT_PROVISIONED, the LENIENT branch, so {@code GET
     *       /api/v1/me} can render onboarding;</li>
     *   <li>the link was erased — DISABLED, a hard stop, because a deleted account answering "onboard
     *       me" would be the one place a deletion reads as an invitation. The link is soft-deleted, so
     *       {@code @SQLRestriction} hides it from every JPA query and this needs a native existence
     *       probe rather than an inference.</li>
     * </ul>
     *
     * <p>A live link whose person was soft-deleted is NOT here: the edge resolves such a caller to a
     * person id, and {@link #authorize(UUID)} refuses it.
     */
    Decision explainAbsence(String keycloakSubject) {
        return resolver.wasErased(keycloakSubject) ? Decision.DISABLED : Decision.NOT_PROVISIONED;
    }
}
