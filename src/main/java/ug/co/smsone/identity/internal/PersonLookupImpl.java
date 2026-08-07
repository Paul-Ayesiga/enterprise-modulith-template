package ug.co.smsone.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.PersonLookup;

/**
 * Implements the shared {@link PersonLookup} port — this is what makes {@code CurrentUser.personId()} a
 * person of ours. One indexed read per authenticated human request, at the edge, once.
 *
 * <p>Thin by design: the issuer→provider mapping and the no-JIT rule are {@link PersonResolver}'s,
 * because they are facts about {@code external_identity}, and the edge must not acquire an opinion about
 * either. Without this bean present every token resolves to no person and the provisioning gate refuses
 * every human — loudly, which is the correct failure for a missing identity module.
 */
@Component
class PersonLookupImpl implements PersonLookup {

    private final PersonResolver resolver;

    PersonLookupImpl(PersonResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Optional<UUID> personId(String issuer, String externalSubject) {
        if (issuer == null || issuer.isBlank()) {
            return Optional.empty();
        }
        return resolver.personIdOf(issuer, externalSubject);
    }
}
