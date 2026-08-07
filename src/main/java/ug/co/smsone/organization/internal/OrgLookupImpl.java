package ug.co.smsone.organization.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.OrgLookup;

/**
 * Implements the shared {@link OrgLookup} port — this is what makes {@code CurrentUser.organizationId()}
 * a tenant key of ours. One indexed read per authenticated request, at the edge, once.
 *
 * <p>Thin by design: the id-vs-alias precedence and the issuer→provider mapping are
 * {@link OrgResolver}'s, because they are facts about {@code external_organization} and the edge must
 * not acquire an opinion about either.
 */
@Component
class OrgLookupImpl implements OrgLookup {

    private final OrgResolver resolver;

    OrgLookupImpl(OrgResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Optional<UUID> organizationId(String issuer, String externalOrgId, String externalAlias) {
        if (issuer == null || issuer.isBlank()) {
            return Optional.empty();
        }
        return resolver.organizationIdOf(issuer, externalOrgId, externalAlias);
    }
}
