package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Everything {@code person_contact} does: the reads other modules resolve through, and the four things a
 * person may do to their own contact book — add, prove, choose a primary, remove.
 *
 * <p>The rules live here, in one class, because they are one rule system and they contradict each other
 * if they are split. "Which address wins", "an unproven address is inert", "a primary must be proven"
 * and "you may not remove the last address that can reach you" are four faces of the same invariant:
 * <b>the platform will only send to, or resolve by, an address somebody has actually established.</b>
 *
 * <p><b>What this slice does NOT do: it never sends a verification message.</b> A proof here is the
 * identity provider's own — a token carrying {@code email_verified} for that address, which is Keycloak
 * telling us the human completed the {@code VERIFY_EMAIL} action it mailed them. That is a complete
 * handshake, not half of one, and it is the same stance the module already takes on linked accounts:
 * proving who owns an address happens at the provider, and this platform records the result. It does
 * mean the only address a person can prove is the one their sign-in account uses, so changing an address
 * is: add it here, change it at the provider, sign in, verify. Issuing OUR OWN e-mailed challenge needs a
 * single-use-token table of the shape {@code signup_request} has (hashed token, expiry, spent-once) and
 * is deliberately not started here — a token flow with nowhere durable to put the token is worse than
 * none.
 */
@Service
class PersonContacts {

    /**
     * A person's contact book is small by nature; this makes "small" a fact the code can rely on rather
     * than a hope. It is what lets {@code /me/contacts} return a plain list instead of a cursor page
     * (ADR 0002 exists to stop unbounded scans, and this collection cannot grow one), and it stops a
     * single account from filling a table that every provisioning run scans by value.
     */
    static final int MAX_PER_KIND = 10;

    /** RFC 5321's ceiling, and the width of {@code person_contact.contact_value}. */
    private static final int MAX_VALUE_LENGTH = 320;

    /** The column width for the human's own label. */
    private static final int MAX_LABEL_LENGTH = 50;

    /**
     * The same shape the signup handshake accepts. Deliberately not an RFC 5322 grammar: the only
     * address that ever matters is one somebody proves, so the syntax check is here to catch a typo,
     * not to decide who owns a mailbox.
     */
    private static final String EMAIL_PATTERN = "[^@\\s]+@[^@\\s]+\\.[^@\\s]+";

    /** Digits with an optional leading +, E.164's ceiling of 15 digits plus room for separators. */
    private static final String PHONE_PATTERN = "\\+?[0-9][0-9 .()-]{4,24}";

    /**
     * Primary first, then proven, then oldest. Primary outranks verified deliberately: primary is what
     * the person (or the admin who invited them) CHOSE, and honouring a verified-but-not-chosen address
     * would quietly redirect their mail. Verified breaks the tie underneath, because an unproven
     * address may belong to somebody else entirely. The id makes the order total, so two calls with the
     * same data can never disagree.
     *
     * <p>The ranking is why {@link #remove} refuses to leave a person whose only remaining address is an
     * unproven claim: a self-added address can only ever win here by outlasting every primary and every
     * verified one, so as long as one of those survives, a claim can never become the address the
     * platform sends to — which is what stops "add an address, delete the rest" being a way to make this
     * platform mail a stranger.
     */
    private static final Comparator<PersonContact> BEST_FIRST = Comparator
            .comparing((PersonContact contact) -> !contact.isPrimary())
            .thenComparing((PersonContact contact) -> contact.getVerifiedAt() == null)
            .thenComparing(PersonContact::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PersonContact::getId);

    private final PersonContactRepository contacts;
    private final AuditLog auditLog;
    private final CrossDatabaseWrites platformTier;
    private final Clock clock;

    PersonContacts(PersonContactRepository contacts, AuditLog auditLog,
            CrossDatabaseWrites platformTier, Clock clock) {
        this.contacts = contacts;
        this.auditLog = auditLog;
        this.platformTier = platformTier;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------------

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
     * <b>The person PROVEN to be reachable at this address</b> — verified rows only.
     *
     * <p>This used to accept an unverified row, on the reasoning that the lookup should still FIND an
     * address in order to report it as unverified. It never could: the port returns an {@code
     * Optional<UUID>} and has no way to say "found, but unproven", so every caller read the id as an
     * answer to "who is this". That made it an account-takeover primitive the moment a person could add
     * an address to their own account — park a claim on somebody's address and this method hands you
     * their identity. Its live caller today is the admin fan-out in {@code NotificationService}, which
     * turns a configured operator e-mail into the {@code person.id} their in-app messages are filed
     * under; an unproven claim satisfying it would have delivered platform-admin notifications to
     * whoever typed the address first.
     *
     * <p>{@code Limit.of(1)} is belt and braces: the partial unique index already makes a live verified
     * address unique, so there is at most one row to find.
     */
    @Transactional(readOnly = true)
    Optional<UUID> findPersonIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        // person_contact is PLATFORM-tier and person is global (ADR 0010 §2.2), but this is reached from
        // the request path, where CurrentUserFilter has pinned the caller's ORG axis on every
        // authenticated route — not only /orgs/**. On a tenant served from its own database that axis
        // resolves to a schema with no platform.person_contact in it, and this threw
        // `relation "platform.person_contact" does not exist` on the first ticket a remote tenant opened,
        // through SupportNotifier's recipient resolution. The hop compares DATABASES, never axes, so it
        // is the same connection and the same transaction wherever no remote datasource is configured.
        return platformTier.callOnPlatform(() ->
                contacts.findVerifiedPersonIdsByValue(ContactKind.EMAIL, email.trim(), Limit.of(1))
                        .stream().findFirst());
    }

    /** The caller's own contact book, best-first within each kind. */
    @Transactional(readOnly = true)
    List<PersonContact> listOwn(UUID personId) {
        return contacts.findAllOf(personId);
    }

    // ---------------------------------------------------------------------------------------------
    // Writes — a person managing their own addresses
    // ---------------------------------------------------------------------------------------------

    /**
     * Records an address its owner claims, UNPROVEN and non-primary.
     *
     * <p><b>A duplicate held by somebody else is deliberately not refused here.</b> V10 says why, and
     * this is the code that keeps its promise: uniqueness on {@code person_contact} covers verified rows
     * only, so a re-created account and a case variant can coexist unproven, and the collision surfaces
     * at VERIFICATION — where a human is present and an error message means something. Refusing at add
     * would also turn this endpoint into an address-existence oracle: "409" would answer "does
     * somebody@corp.com have an account here?" for anyone with a login. A claim costs the other person
     * nothing, because a claim can do nothing (see {@link PersonContact}).
     */
    @Transactional
    PersonContact add(UUID personId, ContactKind kind, String rawValue, String rawLabel) {
        String value = normalize(kind, rawValue);
        String label = trimmedTo(rawLabel, MAX_LABEL_LENGTH, "label");
        List<PersonContact> own = contacts.findAllOf(personId);
        if (own.stream().anyMatch(contact -> contact.getKind() == kind && sameValue(contact, value))) {
            throw new ConflictException("You have already added that address.");
        }
        if (own.stream().filter(contact -> contact.getKind() == kind).count() >= MAX_PER_KIND) {
            throw new ConflictException("You can keep at most " + MAX_PER_KIND + " " + kind
                    + " addresses — remove one first.");
        }
        PersonContact saved = contacts.save(PersonContact.claimed(personId, kind, value, label));
        auditLog.record("identity.contact_added", null, personId.toString(), null,
                kind + "=" + value + " verified=false");
        return saved;
    }

    /**
     * Marks an address proven, on the strength of the caller's own authenticated token.
     *
     * <p>{@code provenAddress} is what the identity provider asserts this caller controls — the {@code
     * email} claim of a token whose {@code email_verified} is true. It arrives as a parameter, read at
     * the edge, because reading a provider's claims below the edge is exactly what this module spent
     * V10 removing; the controller does that read the same way {@link CallerSubject} does, and nothing
     * here knows a claim exists.
     *
     * <p>The duplicate-key catch is the whole point of the verified-only unique index, arriving where
     * V10 said it would. {@code saveAndFlush}, not {@code save}: a queued insert would surface the
     * violation at COMMIT, outside this try, where the catch cannot see it and a legitimate collision
     * degrades into a 500.
     */
    @Transactional
    PersonContact verifyWithProof(UUID personId, UUID contactId, String provenAddress) {
        PersonContact contact = require(personId, contactId);
        if (contact.isVerified()) {
            return contact; // already proven; a second proof is a no-op, not an error
        }
        if (contact.getKind() != ContactKind.EMAIL) {
            throw new ConflictException("Only an e-mail address can be proven today — the platform has "
                    + "no way to challenge a " + contact.getKind() + " address yet.");
        }
        if (provenAddress == null || !provenAddress.equalsIgnoreCase(contact.getContactValue())) {
            throw new ConflictException("Your sign-in account does not prove this address. Change the "
                    + "e-mail on your identity provider account to this one, confirm the message it "
                    + "sends you, sign in again, then retry.");
        }
        contact.verify(clock.instant());
        try {
            contacts.saveAndFlush(contact);
        } catch (DataIntegrityViolationException taken) {
            throw new ConflictException("Another account has already proven that address.");
        }
        auditLog.record("identity.contact_verified", null, personId.toString(),
                contact.getContactValue() + " verified=false", contact.getContactValue() + " verified=true");
        // An UNPROVEN primary is a placeholder: provisioning wrote it when it mailed an invite, before
        // anybody could prove anything. The first proof of that kind replaces it, so the platform stops
        // preferring an address nobody established over one somebody did. A PROVEN primary is a choice
        // and is never overridden — that is what PUT /primary is for.
        PersonContact primary = primaryOf(personId, contact.getKind());
        if (primary == null || !primary.isVerified()) {
            promote(contact, primary);
        }
        return contact;
    }

    /**
     * Makes an address the one of its kind the platform prefers. Refused unless it is proven — the rule
     * is enforced inside {@link PersonContact#makePrimary} so no caller can route around it.
     */
    @Transactional
    PersonContact makePrimary(UUID personId, UUID contactId) {
        PersonContact contact = require(personId, contactId);
        PersonContact current = primaryOf(personId, contact.getKind());
        if (contact.equals(current)) {
            return contact;
        }
        // Fail BEFORE standing the incumbent down. Otherwise a refusal still leaves the person with no
        // primary of that kind — a 409 that changed something.
        if (!contact.isVerified()) {
            throw new ConflictException("Verify this address before making it your primary one.");
        }
        promote(contact, current);
        auditLog.record("identity.contact_primary_changed", null, personId.toString(),
                current == null ? null : current.getContactValue(), contact.getContactValue());
        return contact;
    }

    /**
     * Soft-deletes one of the caller's addresses, refusing the removals that would strand them.
     *
     * <p>Two different disasters, two guards, and they are not the same guard:
     * <ul>
     *   <li><b>Reachability.</b> Something the platform trusts has to remain — a primary or a verified
     *       e-mail. Without this, "add a claim, delete everything else" makes the claim the best address
     *       by default ({@code BEST_FIRST}), and the platform starts mailing an address its owner never
     *       proved: a way to point our outbound mail at a stranger.</li>
     *   <li><b>Resolvability.</b> The last VERIFIED e-mail is the only thing
     *       {@link #findPersonIdByEmail} can answer with, so removing it makes the person unresolvable
     *       by address — invisible to the admin fan-out, and unable to be found again by the humans
     *       supporting them.</li>
     * </ul>
     * Both are e-mail rules; a phone number reaches nothing and resolves nobody, so removing one is
     * always allowed.
     */
    @Transactional
    void remove(UUID personId, UUID contactId) {
        PersonContact contact = require(personId, contactId);
        if (contact.getKind() == ContactKind.EMAIL) {
            List<PersonContact> survivors = contacts.findAllOf(personId).stream()
                    .filter(other -> other.getKind() == ContactKind.EMAIL && !other.equals(contact))
                    .toList();
            if (survivors.isEmpty()) {
                throw new ConflictException(
                        "This is the only address the platform can reach you at — add another first.");
            }
            if (contact.isVerified() && survivors.stream().noneMatch(PersonContact::isVerified)) {
                throw new ConflictException("This is your only verified address, and it is what this "
                        + "platform resolves you by — verify another one first.");
            }
            if (survivors.stream().noneMatch(other -> other.isVerified() || other.isPrimary())) {
                throw new ConflictException("Everything else on file is unproven, and the platform will "
                        + "not fall back to an address nobody has established — verify another first.");
            }
        }
        // DELETE FIRST, AND FLUSH. @SQLDelete turns this into an UPDATE of deleted_at, but Hibernate
        // still schedules it in the delete phase — AFTER every entity update — so promoting a successor
        // in the same flush would put two live is_primary rows past
        // uq_person_contact_primary_live and fail the whole transaction. The flush is what makes the
        // outgoing row invisible to that partial index before the successor claims the flag.
        contacts.delete(contact);
        contacts.flush();
        auditLog.record("identity.contact_removed", null, personId.toString(),
                contact.getKind() + "=" + contact.getContactValue(), null);
        if (contact.isPrimary()) {
            // The reachability guard above guarantees a verified survivor exists whenever the row being
            // removed held the flag (only one row per kind can hold it, so no survivor is primary).
            contacts.findAllOf(personId).stream()
                    .filter(other -> other.getKind() == contact.getKind() && other.isVerified())
                    .min(BEST_FIRST)
                    .ifPresent(successor -> {
                        successor.makePrimary();
                        contacts.save(successor);
                    });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    /**
     * Moves the flag, with a flush in between.
     *
     * <p>The flush is load-bearing, not defensive. {@code uq_person_contact_primary_live} is checked by
     * Postgres per statement, so if Hibernate emits the successor's update before the incumbent's the
     * transaction dies on a constraint violation — and the order two dirty entities flush in is not
     * something a caller gets to specify. Stand down, flush, then promote.
     */
    private void promote(PersonContact successor, PersonContact incumbent) {
        if (incumbent != null) {
            incumbent.standDown();
            contacts.saveAndFlush(incumbent);
        }
        successor.makePrimary();
        contacts.save(successor);
    }

    private PersonContact primaryOf(UUID personId, ContactKind kind) {
        return contacts.findAllOf(personId).stream()
                .filter(contact -> contact.getKind() == kind && contact.isPrimary())
                .findFirst()
                .orElse(null);
    }

    /** Somebody else's contact and a nonexistent one are one 404 — see {@code findByIdAndPersonId}. */
    private PersonContact require(UUID personId, UUID contactId) {
        return contacts.findByIdAndPersonId(contactId, personId)
                .orElseThrow(() -> new NotFoundException("No such contact."));
    }

    private static boolean sameValue(PersonContact contact, String value) {
        return contact.getContactValue().equalsIgnoreCase(value);
    }

    /**
     * Trim, bound, and shape-check.
     *
     * <p>E-mail is lower-cased on the way in, which the index already assumes: {@code
     * uq_person_contact_verified_live} folds case, so {@code Bob@x.com} and {@code bob@x.com} are one
     * address to the database and storing both spellings only makes the list look like it holds two.
     * Nothing else is folded — a phone number has no case, and OTHER is free text this platform makes no
     * claim about.
     */
    private static String normalize(ContactKind kind, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            throw new ValidationException("value is required.", ApiSource.pointer("/data/attributes/value"));
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new ValidationException("value must be at most " + MAX_VALUE_LENGTH + " characters.",
                    ApiSource.pointer("/data/attributes/value"));
        }
        return switch (kind) {
            case EMAIL -> {
                String folded = value.toLowerCase(Locale.ROOT);
                if (!folded.matches(EMAIL_PATTERN)) {
                    throw new ValidationException("value must be a valid e-mail address.",
                            ApiSource.pointer("/data/attributes/value"));
                }
                yield folded;
            }
            case PHONE -> {
                if (!value.matches(PHONE_PATTERN)) {
                    throw new ValidationException("value must be a phone number in international form.",
                            ApiSource.pointer("/data/attributes/value"));
                }
                yield value;
            }
            case OTHER -> value;
        };
    }

    /** Blank reads as absent, so an untouched form field stores NULL rather than a space. */
    private static String trimmedTo(String raw, int max, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > max) {
            throw new ValidationException(field + " must be at most " + max + " characters.",
                    ApiSource.pointer("/data/attributes/" + field));
        }
        return value;
    }
}
