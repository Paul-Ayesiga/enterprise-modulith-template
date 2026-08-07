package ug.co.smsone.identity.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.identity.PersonDirectory;

/** Implements the {@link PersonDirectory} public read port over {@code person} and its own tables. */
@Service
class PersonDirectoryService implements PersonDirectory {

    private final PersonContacts contacts;
    private final ExternalIdentityRepository identities;

    PersonDirectoryService(PersonContacts contacts, ExternalIdentityRepository identities) {
        this.contacts = contacts;
        this.identities = identities;
    }

    @Override
    public Optional<UUID> findPersonIdByEmail(String email) {
        return contacts.findPersonIdByEmail(email);
    }

    @Override
    public Map<UUID, String> emailsByPersonIds(Collection<UUID> personIds) {
        return contacts.emailsByPersonIds(personIds);
    }

    /**
     * A local select where this used to be an HTTP round-trip to Keycloak on every render of the
     * account screen. The links are rows now, so the answer survives Keycloak being slow, being down,
     * or — eventually — not being the only issuer.
     *
     * <p>The provider name goes on the wire as a plain string: {@link IdentityProvider} is this
     * module's vocabulary, and publishing the enum would make adding a provider a change other modules
     * have to recompile against, which is the coupling the varchar column exists to avoid.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LinkedAccount> linkedAccounts(UUID personId) {
        if (personId == null) {
            return List.of();
        }
        return identities.findByPersonIdOrderByLinkedAtAsc(personId).stream()
                .map(identity -> new LinkedAccount(
                        identity.getProvider().name(), identity.getExternalUsername()))
                .toList();
    }
}
