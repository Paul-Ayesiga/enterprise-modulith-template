package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.PersonProvisioned;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.KeycloakUser;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Provisions a person, in the order the identity model demands:
 *
 * <ol>
 *   <li><b>the person is created here</b>, with our own id, plus the {@code person_contact} we will
 *       reach them at;</li>
 *   <li>Keycloak is asked for (or told about) an account for that address;</li>
 *   <li>the baseline realm role is granted and the invite is sent;</li>
 *   <li>the Keycloak subject is recorded as an {@code external_identity} link AGAINST our person.</li>
 * </ol>
 *
 * <p>Steps 1 and 4 used to be one step, done last, keyed on whatever id Keycloak minted — so the human
 * WAS their Keycloak subject and a second identity provider was a migration. Inverting it is the bug
 * being fixed, and it costs one thing worth naming: a Keycloak outage now leaves a person row with no
 * link. That row is inert — resolution runs through {@code external_identity}, so nothing can
 * authenticate as it — and the retry adopts it rather than making a second one.
 *
 * <p><b>Where the idempotency marker moved.</b> The old invariant was "everything before the local row
 * is idempotent and leaves no {@code app_user}", which made a retry able to finish the job. The link
 * plays that part now: it is written LAST, after the role and the invite, so a failure at the invite
 * leaves the person unlinked and the retry re-sends it. Writing the link earlier would report success
 * over an account stranded with no credentials — the exact failure the old ordering guarded against.
 *
 * <p>Keycloak calls run OUTSIDE the local transaction; every step is idempotent.
 */
@Service
class PersonProvisioningService implements PersonProvisioning {

    private final KeycloakUserAdminGateway keycloak;
    private final PersonRepository persons;
    private final PersonContactRepository contacts;
    private final ExternalIdentityRepository identities;
    private final PersonResolver resolver;
    private final Clock clock;
    private final AuditLog auditLog;
    private final ProvisioningProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher events;

    PersonProvisioningService(KeycloakUserAdminGateway keycloak, PersonRepository persons,
            PersonContactRepository contacts, ExternalIdentityRepository identities, PersonResolver resolver,
            Clock clock, AuditLog auditLog, ProvisioningProperties properties,
            TransactionTemplate transactionTemplate, ApplicationEventPublisher events) {
        this.keycloak = keycloak;
        this.persons = persons;
        this.contacts = contacts;
        this.identities = identities;
        this.resolver = resolver;
        this.clock = clock;
        this.auditLog = auditLog;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.events = events;
    }

    @Override
    public ProvisionedPerson provision(ProvisionRequest request) {
        String email = normalize(request.email());
        PersonName name = PersonName.ofParts(request.givenName(), request.familyName());
        UUID personId = findOrCreatePerson(email, name);

        // Already linked at this issuer: fully provisioned before this call, so nothing to do and no
        // second invite. This is the check the old code spelled as "is there an app_user row".
        if (identities.findByPersonIdAndProviderAndIssuer(
                personId, IdentityProvider.KEYCLOAK, resolver.keycloakIssuer()).isPresent()) {
            return new ProvisionedPerson(personId, email, true);
        }

        KeycloakUser account = keycloak.findByEmail(email)
                .orElseGet(() -> keycloak.createUser(email, name.givenName(), name.familyName()));

        // Baseline realm role first: cheapest step, no outward side effect, so a failure here costs
        // nobody an e-mail. Only ever the configured baseline — provisioning can never grant platform
        // authority (ProvisioningProperties rejects a platform role at startup).
        if (properties.grantsDefaultRealmRole()) {
            keycloak.assignRealmRole(account.id(), properties.defaultRealmRole());
        }
        // Invite exactly when the account has no credentials yet: a fresh create, or a retry after the
        // first invite attempt failed. A genuinely pre-existing account (has a password) is never sent
        // a forced credential-reset invite.
        if (!keycloak.hasCredentials(account.id())) {
            keycloak.issueTemporaryCredentials(account.id(), request.requireTotp()
                    ? List.of("UPDATE_PASSWORD", "VERIFY_EMAIL", "CONFIGURE_TOTP")
                    : List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"));
        }
        // One transaction for the link and the audit record that explains it: separate commits would
        // let a crash leave a link no audit accounts for, and the idempotent retry above would never
        // write the missing record afterwards.
        transactionTemplate.executeWithoutResult(tx -> link(personId, account, email));
        // AFTER the commit: this subject may have named a different (erased) person a moment ago, and
        // the edge caches that answer. See PersonResolutionCache for the full invalidation story.
        resolver.forget(account.id());
        return new ProvisionedPerson(personId, email, false);
    }

    /**
     * Adopts an account that already exists in Keycloak, without inviting it: the dev bootstrap's
     * whole job, and the one path that must never send mail or mint a Keycloak user. It shares
     * find-or-create and {@link #link} with {@link #provision} so the two cannot drift on what a
     * provisioned person consists of.
     */
    UUID adopt(String rawEmail, String keycloakSubject) {
        String email = normalize(rawEmail);
        UUID personId = findOrCreatePerson(email, PersonName.UNKNOWN);
        // The same already-linked check provision() makes, and for the same reason: the link is unique
        // per (provider, issuer, subject), so a second insert is a constraint violation, not a no-op.
        // Its absence here was exactly the drift the javadoc above warns about — this method shared
        // find-or-create and link with provision() but not the guard that makes them idempotent. It
        // surfaced the moment both dev bootstraps were enabled together: OrgDevBootstrap provisions the
        // owner first, so PlatformAdminBootstrap then ran into a duplicate link on EVERY startup, and
        // its catch-all reported it as "is Keycloak reachable?" — a misdiagnosis of its own database.
        if (identities.findByPersonIdAndProviderAndIssuer(
                personId, IdentityProvider.KEYCLOAK, resolver.keycloakIssuer()).isPresent()) {
            return personId;
        }
        transactionTemplate.executeWithoutResult(
                tx -> link(personId, new KeycloakUser(keycloakSubject, email), email));
        resolver.forget(keycloakSubject); // after the commit, same reason as provision()
        return personId;
    }

    /**
     * The person for this address, created if there is none.
     *
     * <p>The advisory lock is what makes "created if there is none" true under concurrency. Uniqueness
     * on {@code person_contact} covers VERIFIED addresses only — deliberately, so a re-created account
     * and a case variant can coexist unproven — which means nothing in the schema stops two concurrent
     * invites of the same new address from inserting two people. See
     * {@link PersonRepository#lockEmail}.
     */
    private UUID findOrCreatePerson(String email, PersonName name) {
        return transactionTemplate.execute(tx -> {
            persons.lockEmail(email);
            Optional<UUID> existing = contacts
                    .findPersonIdsByValue(ContactKind.EMAIL, email, Limit.of(1))
                    .stream().findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
            Instant now = clock.instant();
            Person person = persons.save(Person.invited(name, now));
            contacts.save(PersonContact.primaryEmail(person.getId(), email));
            // Published here, not registered on the aggregate: person.id is assigned at persist, so an
            // event built in the factory would carry null. Inside the transaction, so the Modulith
            // publication registry commits it with the row it describes.
            events.publishEvent(new PersonProvisioned(person.getId(), email, now));
            auditLog.record("identity.person_provisioned", null, person.getId().toString(), null,
                    "email=" + email);
            return person.getId();
        });
    }

    /**
     * Records the Keycloak subject against our person.
     *
     * <p>The duplicate-key catch is the concurrent-provision outcome, not an error: two callers racing
     * to link the same account both mean "this person signs in with this subject", and the winner's row
     * IS that fact. A violation that does NOT resolve to an existing link is a different bug and
     * rethrows.
     */
    private void link(UUID personId, KeycloakUser account, String email) {
        try {
            // saveAndFlush, not save: persist() only queues the insert, so the unique-index violation
            // would otherwise surface at COMMIT — outside this try, where the catch below can never see
            // it and the race degrades into a 500. Flushing here is what makes the catch reachable.
            identities.saveAndFlush(ExternalIdentity.link(personId, IdentityProvider.KEYCLOAK,
                    resolver.keycloakIssuer(), account.id(), email, clock.instant()));
        } catch (DataIntegrityViolationException ex) {
            if (identities.findByProviderAndIssuerAndExternalSubject(
                    IdentityProvider.KEYCLOAK, resolver.keycloakIssuer(), account.id()).isEmpty()) {
                throw ex;
            }
            return; // the winner already wrote it — and already wrote the audit row below
        }
        auditLog.record("identity.external_identity_linked", null, personId.toString(), null,
                "provider=" + IdentityProvider.KEYCLOAK + " subject=" + account.id());
    }

    /**
     * Addresses are compared case-insensitively everywhere; trimming here keeps that one rule honest.
     *
     * <p>The blank check is new, and it is a direct consequence of the inversion. Under the old order a
     * missing address failed at Keycloak, which refuses a user with no username, and nothing local was
     * written. Now the person row goes in FIRST, so the same bad call would leave a permanent person
     * nobody can ever reach or match — the port has to refuse it here or not at all.
     */
    private static String normalize(String email) {
        String trimmed = email == null ? "" : email.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("An e-mail address is required to provision a person.",
                    ApiSource.pointer("/data/attributes/email"));
        }
        return trimmed;
    }
}
