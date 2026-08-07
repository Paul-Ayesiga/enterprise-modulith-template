package ug.co.smsone.identity.internal;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads over {@code person_contact}, and the single home of the rule "which of a person's addresses is
 * THE address".
 *
 * <p>A person may hold several e-mails: one marked primary, older ones kept for history, an unverified
 * one somebody just added. Every caller that wants "their e-mail" needs the same answer, so the
 * ordering lives here once rather than in each query — and it is expressed in Java rather than SQL
 * because it is a three-way preference no index can encode without a window function.
 */
@Service
class PersonContacts {

    /**
     * Primary first, then proven, then oldest. Primary outranks verified deliberately: primary is what
     * the person (or the admin who invited them) CHOSE, and honouring a verified-but-not-chosen address
     * would quietly redirect their mail. Verified breaks the tie underneath, because an unproven
     * address may belong to somebody else entirely. The id makes the order total, so two calls with the
     * same data can never disagree.
     */
    private static final Comparator<PersonContact> BEST_FIRST = Comparator
            .comparing((PersonContact contact) -> !contact.isPrimary())
            .thenComparing((PersonContact contact) -> contact.getVerifiedAt() == null)
            .thenComparing(PersonContact::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PersonContact::getId);

    private final PersonContactRepository contacts;

    PersonContacts(PersonContactRepository contacts) {
        this.contacts = contacts;
    }

    /** One e-mail per person, for a page of ids. People with no address on file are simply absent. */
    @Transactional(readOnly = true)
    Map<UUID, String> emailsByPersonIds(Collection<UUID> personIds) {
        if (personIds == null || personIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> emails = new LinkedHashMap<>();
        contacts.findByPersonIds(personIds, ContactKind.EMAIL).stream()
                .sorted(BEST_FIRST)
                // putIfAbsent, not put: the sort already placed the winner first, so the first row seen
                // for a person is the answer and every later one is history.
                .forEach(contact -> emails.putIfAbsent(contact.getPersonId(), contact.getContactValue()));
        return Map.copyOf(emails);
    }

    @Transactional(readOnly = true)
    Optional<String> emailOf(UUID personId) {
        return personId == null ? Optional.empty()
                : Optional.ofNullable(emailsByPersonIds(List.of(personId)).get(personId));
    }

    /**
     * The person reachable at this address. {@code Limit.of(1)} because the repository has already
     * ordered the candidates and only the winner is ever wanted — reading the rest would be work done
     * to throw away.
     */
    @Transactional(readOnly = true)
    Optional<UUID> findPersonIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return contacts.findPersonIdsByValue(ContactKind.EMAIL, email.trim(), Limit.of(1)).stream().findFirst();
    }
}
