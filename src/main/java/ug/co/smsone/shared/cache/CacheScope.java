package ug.co.smsone.shared.cache;

/**
 * Which tenancy axis a cache's entries belong to (ADR 0010 §3.5). Every cache name declares one in
 * {@link CacheRegistry}, and an undeclared name cannot be obtained at all — a cache nobody classified
 * is the one that leaks.
 *
 * <p>There are exactly two, and the pair is not symmetric: {@link #GLOBAL} is a claim that the value
 * is the same answer for every tenant, {@link #TENANT} is a claim that it is not. The second is the
 * safe default to reach for; the first has to be argued, which is why each {@code GLOBAL} entry in the
 * registry carries the argument next to it.
 */
public enum CacheScope {

    /**
     * One answer for the whole installation. Two shapes qualify and nothing else does: the value is
     * read from a table with no {@code org_id} at all ({@code setting}, {@code feature_flag},
     * {@code translation}), or the question is asked BEFORE a tenant is known and therefore cannot
     * carry one — which is what {@code person-by-subject} ("who is this login") and
     * {@code org-by-external-id} ("which tenant is this claim") do. A tenant prefix on those two would
     * be both wrong and impossible: the request that asks them has no axis yet, and pinning one would
     * need the answer they are being asked for.
     */
    GLOBAL,

    /**
     * One answer per organization. The tenant is prefixed into the key in BOTH levels — Caffeine and
     * Valkey — and a lookup with no tenant axis throws rather than falling back to a shared key. Both
     * halves matter: prefixing one level only would leave the other serving one tenant's rows to
     * another for its whole TTL, and a fallback key would be the leak itself, silently.
     */
    TENANT
}
