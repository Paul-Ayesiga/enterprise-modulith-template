package ug.co.smsone.identity.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.directory.PersonProjection;
import ug.co.smsone.shared.directory.PersonProjections;

/**
 * Implements the shared {@link PersonProjections} port over {@code person} and {@code person_contact}.
 *
 * <p><b>Two statements per batch, whatever N is.</b> That is the entire contract and it is worth
 * stating as a number: {@code findAllById} is one {@code where id in (…)}, and
 * {@link PersonContacts#emailsByPersonIds} is one more over {@code person_contact}. A future edit
 * that resolves anything else per person — a linked-account count, a profile avatar — puts the N+1
 * back on the server side of a sideload that exists to remove it, and
 * {@code PersonProjectionsTest.oneBatchCostsTheSameNumberOfStatementsAsOnePerson} is what fails then.
 *
 * <p><b>The address comes from {@code PersonContacts}, not from a query of its own</b>, because
 * "which of a person's addresses wins" is a rule with four clauses (primary outranks verified,
 * verified outranks unproven, oldest breaks the tie, id makes the order total) and it already lives
 * there. A second SQL expression of it would be a second answer to the question the platform mails
 * to.
 *
 * <p><b>Reads platform rows on whichever axis the caller is pinned to, correctly and by
 * construction.</b> {@code Person} and {@code PersonContact} both declare
 * {@code @Table(schema = "platform")}, and Hibernate honours a declared schema whatever the
 * connection's {@code search_path} is (ADR 0010 §2, enforced by {@code PlatformSchemaQualificationTest}).
 * So the org-scoped member list can call this from inside its tenant's axis and still read the person
 * graph. That is a fact about the qualification and not about this class — it is why the read needs
 * no {@code TenantContext} dance, and it is also precisely the join that ADR 0011 turns into a wire
 * call, at which point the port stays and this implementation is the thing that gets swapped.
 *
 * <p>Erased people are absent rather than blank: {@code @SQLRestriction("deleted_at is null")} applies
 * to {@code findAllById}, so a soft-deleted person simply has no entry, which is what the port's
 * javadoc promises the caller will render as a bare id.
 */
@Component
class PersonProjectionsImpl implements PersonProjections {

    /**
     * How many ids one pair of statements carries. Same bound and same reason as
     * {@code PersonProjector.BATCH}: a single {@code in (…)} of unbounded width is a query text no
     * statement cache can hold, and pgjdbc's parameter limit is a hard 65,535 above it. A page of
     * members is at most {@code CursorPageRequest.MAX_SIZE} (100), so production takes one pass —
     * the bound exists for the caller who one day hands this a whole roster.
     */
    private static final int BATCH = 500;

    private final PersonRepository persons;
    private final PersonContacts contacts;

    PersonProjectionsImpl(PersonRepository persons, PersonContacts contacts) {
        this.persons = persons;
        this.contacts = contacts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PersonProjection> projectionsOf(Set<UUID> personIds) {
        if (personIds == null || personIds.isEmpty()) {
            // No statement at all for an empty page. `in ()` is not valid SQL and Spring Data would
            // answer an empty list anyway — this makes the zero-cost path explicit rather than lucky.
            return Map.of();
        }
        List<UUID> ordered = new ArrayList<>(personIds);
        Map<UUID, PersonProjection> projections = new LinkedHashMap<>();
        for (int from = 0; from < ordered.size(); from += BATCH) {
            List<UUID> batch = ordered.subList(from, Math.min(from + BATCH, ordered.size()));
            List<Person> people = persons.findAllById(batch);
            Map<UUID, String> emails = contacts.emailsByPersonIds(batch);
            for (Person person : people) {
                PersonName name = person.getName();
                projections.put(person.getId(), new PersonProjection(person.getId(),
                        name.formattedName(), name.givenName(), name.familyName(),
                        person.getStatus().name(), emails.get(person.getId())));
            }
        }
        return projections;
    }
}
