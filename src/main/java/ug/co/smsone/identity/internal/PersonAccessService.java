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
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.security.PlatformAdmins;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * The access decision for an authenticated human, with lazy {@code INVITED → ACTIVE} activation — plus
 * the module's {@code person} queries, so no controller touches a repository (§3.1).
 *
 * <p><b>Every entry point takes a {@code person.id}</b> — the edge resolves it now, so the resolve step
 * this class used to perform is gone. The one exception is {@link #explainAbsence(String)}, which is
 * handed a raw subject precisely because there is no person to name: only this module can say whether an
 * unresolvable subject was never linked or was erased, and that distinction is a provisioning policy, not
 * an edge concern.
 *
 * <p><b>It also owns the two writes that DECIDE that decision</b> — {@link #disable} and {@link #enable},
 * the administrative half of the lifecycle. They live beside {@link #authorize} rather than in a service
 * of their own because they are the same question from the other end: {@code authorize} reads the status
 * on every request and these two set it, and separating them is how the rule about what a restored person
 * comes back AS ends up written down in one place and enforced in another. Editing a person's NAME is a
 * different concern with a different floor and lives in {@link PersonNameService}.
 */
@Service
class PersonAccessService {

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /**
     * Distinct from {@code identity.person_disabled_by_reconciliation}, which the nightly sweep files.
     * Two actions, not one action plus a reason field: the pair is the record of WHY access stopped, the
     * names are stable wire-ish contracts a review filters on, and their actors differ by construction —
     * an operator here, {@code null} there, because nobody performs a scheduled comparison.
     */
    private static final String PERSON_DISABLED = "identity.person_disabled";

    private static final String PERSON_ENABLED = "identity.person_enabled";

    enum Decision {
        ALLOWED,
        NOT_PROVISIONED,
        DISABLED
    }

    /**
     * One person on the platform surface: identity, what they are called, and how to reach them.
     *
     * <p>It carries the whole {@link PersonName} rather than just {@code formattedName} because the
     * operator surface has two readers of it now — a listing that renders the display value, and the
     * single read an operator opens before correcting a typo, which needs the components the typo is
     * in. Projecting the name down to one string here would have forced a second query shape for the
     * second reader.
     */
    record PersonSummary(UUID personId, PersonName name, String email, ProvisioningStatus status) {
    }

    /**
     * What {@code /me} needs about the caller's own account. {@code personId} and {@code email} are both
     * null before provisioning — there is no person yet, so there is nothing to reach them at either —
     * and {@code name} is {@link PersonName#UNKNOWN} for the same reason. Never null: "we know of no
     * name" is a state every caller must render, and an absent object would make it a second one.
     */
    record SelfView(UUID personId, String email, PersonName name, String provisioningStatus) {
    }

    private final PersonRepository persons;
    private final PersonResolver resolver;
    private final PersonContacts contacts;
    private final KeycloakUserAdminGateway keycloak;
    private final PlatformAdmins platformAdmins;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLog auditLog;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    PersonAccessService(PersonRepository persons, PersonResolver resolver, PersonContacts contacts,
            KeycloakUserAdminGateway keycloak, PlatformAdmins platformAdmins,
            CurrentUserProvider currentUserProvider, AuditLog auditLog,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.persons = persons;
        this.resolver = resolver;
        this.contacts = contacts;
        this.keycloak = keycloak;
        this.platformAdmins = platformAdmins;
        this.currentUserProvider = currentUserProvider;
        this.auditLog = auditLog;
        this.transactionTemplate = transactionTemplate;
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
            return unprovisioned();
        }
        return persons.findById(personId)
                .map(person -> new SelfView(person.getId(), contacts.emailOf(person.getId()).orElse(null),
                        person.getName(), person.getStatus().name()))
                .orElseGet(PersonAccessService::unprovisioned);
    }

    /**
     * One person, for the operator surface's single read. Throws rather than returning an Optional
     * because its caller wants the 404: {@code @SQLRestriction} makes an erased person and one that
     * never existed the same absence here, and to an operator both mean "not something you can look
     * at". {@link PersonNameService} answers the same 404 from its own lookup rather than calling this
     * one — it needs the managed entity to write to, not a projection of it.
     */
    @Transactional(readOnly = true)
    PersonSummary require(UUID personId) {
        return persons.findById(personId)
                .map(this::summaryOf)
                .orElseThrow(() -> new NotFoundException("No such user."));
    }

    private PersonSummary summaryOf(Person person) {
        return new PersonSummary(person.getId(), person.getName(),
                contacts.emailOf(person.getId()).orElse(null), person.getStatus());
    }

    private static SelfView unprovisioned() {
        return new SelfView(null, null, PersonName.UNKNOWN, "UNPROVISIONED");
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
        return window.map(person -> new PersonSummary(person.getId(), person.getName(),
                emails.get(person.getId()), person.getStatus()));
    }

    /**
     * An administrator suspends a human. Returns the person as they now stand.
     *
     * <p><b>This bites on the caller's very next request, and it needs no cache eviction to do so.</b>
     * That is worth stating because the shape invites the opposite conclusion: the edge does resolve
     * ({@code issuer}, {@code subject}) → {@code person.id} through a two-level cache
     * ({@link PersonResolutionCache}), and a suspension that waited for a TTL would be a security hole
     * wearing a latency costume. It does not, because that cache holds an IDENTITY, never a decision —
     * who a subject is, not whether they may in — and the decision is {@link PersonRepository#statusById},
     * which is deliberately uncached. Calling {@code resolver.forget(...)} here would be cargo cult: it
     * would drop a still-correct entry (the link is unchanged — a disabled person is the same person),
     * cost a database read on their next attempt, and prove nothing. The change that WOULD open the hole
     * is making {@code statusById} cacheable, and {@code PersonLifecycleApiTest} is what fails then.
     *
     * <p>The other two live readers of a person's status re-read it per request for the same reason and
     * are covered by the same write: {@code ProvisioningGateFilter} on every {@code /api/**} call, and
     * {@code ImpersonationLookupImpl} on both ends of a live session — so suspending an operator kills
     * the reach they are holding, and suspending a target closes the session worn over them, on the next
     * request either way.
     *
     * <p>Not {@code @Transactional}: {@link #refuseIfLastSuperAdmin} is a Keycloak round-trip, and a
     * remote call inside a transaction pins a Hikari connection for the length of somebody else's outage
     * (§4.3). The write opens afterwards through the template, which is also what makes the row and the
     * {@code audit_log} row explaining it commit together.
     */
    PersonSummary disable(UUID personId) {
        // Read outside the write, and NOT through require(): that method is @Transactional, and calling
        // it from here is a self-invocation the proxy never sees (§4.3) — it would appear to open a
        // read-only transaction and would not. 404 for an erased person too, because @SQLRestriction
        // hides them and to an operator an erased account is gone.
        Person current = persons.findById(personId)
                .orElseThrow(() -> new NotFoundException("No such user."));
        if (current.getStatus() == ProvisioningStatus.DISABLED) {
            // Idempotent (AGENTS §6): re-disabling changes nothing, so it must not file an audit row
            // claiming a change — nor pay for the remote guard below, which exists to protect a
            // transition that is not about to happen.
            return summaryOf(current);
        }
        refuseIfSelf(personId);
        refuseIfLastSuperAdmin(personId);
        return transactionTemplate.execute(tx -> {
            Person person = persons.findById(personId)
                    .orElseThrow(() -> new NotFoundException("No such user."));
            if (person.getStatus() == ProvisioningStatus.DISABLED) {
                return summaryOf(person); // lost the race with a concurrent disable — same rule as above
            }
            ProvisioningStatus from = person.getStatus();
            person.disable(clock.instant());
            persons.save(person);
            auditLog.record(PERSON_DISABLED, null, personId.toString(),
                    "status=" + from, "status=" + ProvisioningStatus.DISABLED);
            return summaryOf(person);
        });
    }

    /**
     * An administrator restores a human, to the status they held before access was taken away — see
     * {@link Person#enable()} for why that is INVITED for somebody who never turned up and why nothing
     * needs a column to remember it.
     *
     * <p><b>The provider check is what stops an admin and the nightly sweep fighting.</b>
     * {@code IdentityReconciliationJob} disables people whose Keycloak account has disappeared; re-enable
     * one of those and tonight's pass disables them again, forever, with the operator surface claiming
     * one thing and the system doing another every morning. The loop is closed HERE rather than by
     * teaching the job to skip manually-restored rows, for two reasons: the job must stay a pure reader
     * of Keycloak's answer (a "but an admin said so" exemption is how a deleted account keeps access),
     * and asking the provider at restore time answers the question the operator actually has — is this
     * human able to sign in again — from the system that owns it, rather than from a local flag that was
     * true last night.
     *
     * <p>Only {@code ABSENT} refuses. {@code UNKNOWN} means the lookup failed, and it restores, on
     * purpose and symmetrically with the job: that job never acts on {@code UNKNOWN} either, so no loop
     * can start from it, and refusing would make an operator's ability to correct a mistake hostage to
     * Keycloak's uptime. Nor is permitting it a way in — Keycloak authenticates, so a person whose
     * account really is gone cannot mint a token whatever this table says.
     *
     * <p>No self-guard and no last-super-admin guard, unlike {@link #disable}: neither can arise. A
     * suspended person cannot authenticate past the gate to restore themselves, and restoring somebody
     * only ever widens the set of people holding a role.
     */
    PersonSummary enable(UUID personId) {
        Person current = persons.findById(personId) // see disable() on why not require()
                .orElseThrow(() -> new NotFoundException("No such user."));
        if (current.getStatus() != ProvisioningStatus.DISABLED) {
            return summaryOf(current); // already has access — no audit row, and no remote call
        }
        refuseIfProviderAccountIsGone(personId);
        return transactionTemplate.execute(tx -> {
            Person person = persons.findById(personId)
                    .orElseThrow(() -> new NotFoundException("No such user."));
            if (person.getStatus() != ProvisioningStatus.DISABLED) {
                return summaryOf(person);
            }
            person.enable();
            persons.save(person);
            // Both sides spelled out: "restored" alone cannot answer whether an invitation was marked
            // accepted, which is the one thing this operation can get wrong.
            auditLog.record(PERSON_ENABLED, null, personId.toString(),
                    "status=" + ProvisioningStatus.DISABLED, "status=" + person.getStatus());
            return summaryOf(person);
        });
    }

    /**
     * Suspending yourself locks you out of the surface you would need to undo it, and if you were the
     * last operator awake it locks out everyone. 409 rather than 403 deliberately: the caller IS
     * authorized — a {@code platform-admin} may disable people — and the request conflicts with the state
     * of the target, that state being "it is you".
     *
     * <p>{@code accountablePersonId()} rather than {@code personId()} so the guard and the audit row name
     * the same human. The two differ only under impersonation, which cannot reach this endpoint at all:
     * the swapped principal carries an empty authority collection, so {@code hasRole('platform-admin')}
     * fails before anything here runs.
     */
    private void refuseIfSelf(UUID personId) {
        UUID caller = currentUserProvider.requireCurrentUser().accountablePersonId();
        if (personId.equals(caller)) {
            throw new ConflictException("You cannot suspend your own account. Ask another platform "
                    + "administrator to do it.");
        }
    }

    /**
     * The one account whose loss cannot be repaired from inside the application. A {@code platform-admin}
     * who is suspended by mistake can be restored by any other admin; the last {@code platform-superadmin}
     * is the tier that grants tiers, and losing it means waiting for {@code PlatformAdminBootstrap} to
     * re-seed one on the next restart. Same guard, same port and same reasoning as the erasure refusal in
     * {@code ComplianceService} — including that {@link PlatformAdmins} fails OPEN when Keycloak cannot be
     * reached, so an outage never blocks an ordinary suspension.
     */
    private void refuseIfLastSuperAdmin(UUID personId) {
        if (platformAdmins.isSoleSuperAdmin(personId)) {
            throw new ConflictException("This account is the last platform super-admin; grant that role "
                    + "to another account before suspending this one.");
        }
    }

    private void refuseIfProviderAccountIsGone(UUID personId) {
        String subject = resolver.keycloakSubjectOf(personId).orElse(null);
        if (subject == null) {
            // No Keycloak link at all — federated elsewhere, or caught mid-provisioning. The nightly
            // sweep counts these `unlinked` and skips them, so there is no loop to prevent and nothing
            // to ask. Same shape, and the same reasoning, as PlatformAdminsImpl's unlinked branch.
            return;
        }
        if (keycloak.accountPresence(subject) == KeycloakUserAdminGateway.AccountPresence.ABSENT) {
            throw new ConflictException("This person's account no longer exists in the identity provider, "
                    + "so restoring access here would be reversed by the nightly identity reconciliation "
                    + "and they still could not sign in. Re-create or restore their provider account "
                    + "first, then enable them here.");
        }
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
