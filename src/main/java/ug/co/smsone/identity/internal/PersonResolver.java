package ug.co.smsone.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one seam where a provider subject becomes a {@code person.id}, and back.
 *
 * <p><b>Everything above this class speaks person ids.</b> The edge validates a token and knows an
 * {@code iss}/{@code sub} pair; the platform knows people. Somebody has to translate, and it is this
 * module, because it owns {@code external_identity} — the only table permitted to hold an identifier
 * minted elsewhere. Confining the translation to one class is what makes "nothing below the edge sees a
 * Keycloak subject" checkable rather than aspirational, and it is the same discipline that keeps
 * Keycloak's {@code firstName}/{@code lastName} vocabulary inside {@link KeycloakUserAdminGateway}.
 *
 * <p>There is <b>no JIT</b> here either: an unknown subject resolves to nothing. A validated token
 * proves who an issuer thinks you are; it has never been permission to create an account.
 */
@Component
class PersonResolver {

    private final ExternalIdentityRepository identities;
    private final PersonResolutionCache cache;
    private final String keycloakIssuer;

    /**
     * The issuer is read from the resource-server property, not from a key of our own, and not composed
     * from {@code app.keycloak-admin.base-url} + {@code realm}. {@code external_identity.issuer} has to
     * be byte-identical to the {@code iss} the edge accepted, or the lookup above misses and a perfectly
     * valid token resolves to no person. A second key would be a second thing to keep in step, and the
     * failure when it drifts is silent: every human 403s at once with nothing in the logs naming why.
     */
    PersonResolver(ExternalIdentityRepository identities, PersonResolutionCache cache,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String keycloakIssuer) {
        this.identities = identities;
        this.cache = cache;
        this.keycloakIssuer = keycloakIssuer;
    }

    /** The person behind a Keycloak subject, or empty when nothing here is linked to it. */
    Optional<UUID> personIdOf(String keycloakSubject) {
        return personIdOf(keycloakIssuer, keycloakSubject);
    }

    /**
     * The person behind a subject AT A NAMED ISSUER — what the edge asks, because the issuer it holds is
     * the one the resource server just validated rather than the one this deployment configures.
     *
     * <p>Those are the same string today and must not be assumed to stay so: a deployment that later
     * trusts a second issuer keeps resolving both without a second key to keep in step, and — more to
     * the point — a subject from an issuer we do NOT know resolves to nothing instead of being matched
     * against this realm's disjoint subject space (V10: a staging realm and a prod realm both emit
     * {@code KEYCLOAK}).
     *
     * <p><b>Cached, and the guards run first on purpose.</b> This is the platform's hottest read — once
     * per authenticated request — for a row that cannot change while it lives, so the answer goes
     * through {@link PersonResolutionCache}; that class documents the whole invalidation story. The
     * provider derivation, the blank check and the trim happen HERE so the cache key is the normalized
     * triple and an unresolvable issuer never reaches the cache at all.
     *
     * <p><b>Stale-while-unreachable (ADR 0011 §2).</b> When the read cannot be ANSWERED — the platform
     * database unreachable, never an answer we dislike — the last authoritative answer this process
     * holds is served instead, up to the hard ceiling measured from that entry's own last refresh. Past
     * it, {@code PlatformUnreachableException} (503 + Retry-After) rather than empty: empty here flows
     * into the provisioning gate as ACCOUNT_NOT_PROVISIONED, which would tell a paying user they don't
     * exist because a database is down. Query-shaped failures rethrow untouched.
     */
    Optional<UUID> personIdOf(String issuer, String externalSubject) {
        IdentityProvider provider = providerOf(issuer);
        if (provider == null || externalSubject == null || externalSubject.isBlank()) {
            return Optional.empty();
        }
        // Text, then parsed here: see PersonResolutionCache on why a bare UUID cannot cross L2.
        String personId;
        try {
            personId = cache.personIdTextOf(provider, issuer, externalSubject.trim());
        } catch (RuntimeException failure) {
            personId = cache.lastKnownOr(provider, issuer, externalSubject.trim(), failure);
        }
        return personId == null ? Optional.empty() : Optional.of(UUID.fromString(personId));
    }

    /**
     * Drops a subject's cached resolution. Called by {@link PersonProvisioningService} after the link
     * transaction commits — the one moment a subject can start naming a DIFFERENT person than it did
     * (erase an account, then re-provision the same Keycloak user).
     *
     * <p>After the commit, never inside it: evicting while the link is still uncommitted leaves a window
     * in which a concurrent request re-reads the pre-link state and caches it back.
     */
    void forget(String keycloakSubject) {
        if (keycloakSubject == null || keycloakSubject.isBlank()) {
            return;
        }
        cache.forget(IdentityProvider.KEYCLOAK, keycloakIssuer, keycloakSubject.trim());
    }

    /**
     * Which vocabulary word names this issuer. One entry today, and deliberately a lookup rather than a
     * branch on a provider argument the caller passes: deciding that KEYCLOAK-vs-GOOGLE names an issuer
     * is the code change "adding a provider is a row" exists to forbid, so the day there is a second one
     * this reads a table or a property and every caller — the edge above all — stays untouched.
     *
     * <p>Returning null for an unknown issuer is the point. Guessing KEYCLOAK would drop the leading
     * column of {@code uq_external_identity_subject_live} out of the lookup and let one realm's subjects
     * match another's rows.
     */
    private IdentityProvider providerOf(String issuer) {
        return keycloakIssuer.equals(issuer) ? IdentityProvider.KEYCLOAK : null;
    }

    /**
     * Whether a subject WAS linked here and the link has since been erased. Distinct from "never seen":
     * one is a revocation, the other is somebody who has not been provisioned yet, and only the second
     * may be shown an onboarding screen.
     */
    @Transactional(readOnly = true)
    boolean wasErased(String keycloakSubject) {
        if (keycloakSubject == null || keycloakSubject.isBlank()) {
            return false;
        }
        return identities.existsDeletedBySubject(
                IdentityProvider.KEYCLOAK.name(), keycloakIssuer, keycloakSubject.trim());
    }

    /**
     * The reverse direction, needed only by the calls that talk TO Keycloak (its admin API knows people
     * by their subject and nothing else). Empty when the person has no Keycloak link — a person
     * federated only elsewhere, or one caught mid-provisioning.
     */
    @Transactional(readOnly = true)
    Optional<String> keycloakSubjectOf(UUID personId) {
        if (personId == null) {
            return Optional.empty();
        }
        return identities
                .findByPersonIdAndProviderAndIssuer(personId, IdentityProvider.KEYCLOAK, keycloakIssuer)
                .map(ExternalIdentity::getExternalSubject);
    }

    /** The issuer every KEYCLOAK link this deployment writes is stamped with. */
    String keycloakIssuer() {
        return keycloakIssuer;
    }
}
