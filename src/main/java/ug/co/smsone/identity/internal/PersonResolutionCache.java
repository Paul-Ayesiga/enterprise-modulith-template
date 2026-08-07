package ug.co.smsone.identity.internal;

import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * The cached half of {@link PersonResolver}: {@code (provider, issuer, subject) → person.id}, the one
 * lookup every authenticated human request performs. A separate bean so the cached call goes through
 * the proxy — self-invocation would not — the same reason {@code organization.internal.PermissionResolver}
 * is its own bean.
 *
 * <p><b>Why this is cacheable at all.</b> The row it reads is immutable: {@code person_id},
 * {@code provider}, {@code issuer} and {@code external_subject} are all {@code updatable = false} on
 * {@link ExternalIdentity}, so a live link never re-points at a different person. The only way the
 * answer changes is that the link stops being live (erasure soft-deletes it) or a NEW link is written
 * for the same subject (erase, then re-provision the same Keycloak account).
 *
 * <p><b>It caches an identity, never a decision.</b> This entry says who a subject is, not whether they
 * may in. {@code ProvisioningGateFilter} still reads {@code person.status} live on every request, so a
 * disabled or erased account is refused on the very next one no matter what is cached here — a stale
 * entry can only ever fail closed.
 *
 * <p><b>Invalidation, in full:</b>
 * <ul>
 *   <li>absences are NOT cached ({@code unless = "#result == null"}), so a person provisioned for the
 *       first time resolves on their next request with nothing to evict — and a test that seeds
 *       {@code external_identity} directly cannot be poisoned by an earlier miss;</li>
 *   <li>{@link PersonProvisioningService} calls {@link PersonResolver#forget(String)} after the link
 *       transaction COMMITS, which is the one path that can point a subject at a different person;</li>
 *   <li>erasure needs no reach into this module: the entry it strands names a soft-deleted person, whom
 *       the gate refuses, and the re-provision that would make the subject live again evicts above.</li>
 * </ul>
 *
 * <p><b>The cached value is the id AS TEXT, and that is not a style choice.</b> L2 stores JSON, and a
 * JSON SCALAR cannot carry a type id the way an object can — a cached {@code UUID} reads back from
 * Valkey as a {@code String}, which would ClassCastException inside the CGLIB proxy on every
 * authenticated request, but only once L1 had expired. {@code ValkeyCacheIntegrationTest} pins both
 * halves of that: the failure for a bare UUID, and the round trip for the text form, which survives
 * precisely because {@code String} is what a JSON scalar reconstructs as either way.
 */
@Component
class PersonResolutionCache {

    static final String CACHE = "person-by-subject";

    /**
     * The key is built from strings only. {@code provider.name()} rather than the enum because the L2
     * key has to render as text, and '|' rather than ':' because an issuer is a URL full of colons.
     * Every component is non-null by the time {@link PersonResolver} calls: it derives the provider,
     * and rejects a null or blank subject, before it gets here.
     */
    private static final String KEY = "#provider.name() + '|' + #issuer + '|' + #externalSubject";

    private final ExternalIdentityRepository identities;

    PersonResolutionCache(ExternalIdentityRepository identities) {
        this.identities = identities;
    }

    /**
     * Public for the same reason {@code PermissionResolver.resolve} is: the caching advice is applied by
     * a proxy, and a public method is the shape that is advised whatever proxying strategy is in force.
     *
     * <p>No {@code @Transactional} here. The read is a single statement and the repository already runs
     * it read-only, so a cache HIT now costs the edge no connection checkout at all — which was half the
     * point of caching it.
     */
    @Cacheable(cacheNames = CACHE, key = KEY, unless = "#result == null")
    public String personIdTextOf(IdentityProvider provider, String issuer, String externalSubject) {
        return identities.personIdByLink(provider, issuer, externalSubject).map(UUID::toString).orElse(null);
    }

    /** Drops one subject's entry. Called after a link commits — see the class javadoc. */
    @CacheEvict(cacheNames = CACHE, key = KEY)
    public void forget(IdentityProvider provider, String issuer, String externalSubject) {
        // The eviction IS the behaviour; the annotation does the work.
    }
}
