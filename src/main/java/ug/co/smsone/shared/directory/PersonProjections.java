package ug.co.smsone.shared.directory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port for resolving a BOUNDED SET of {@code person.id}s to their {@link PersonProjection} in one
 * round trip, implemented by the module that owns {@code person}.
 *
 * <h2>Why this exists: the member list was N+1 over HTTP</h2>
 *
 * <p>{@code GET /api/v1/admin/orgs/{orgId}/members} and {@code GET /api/v1/orgs/{orgId}/members}
 * answer with ids. Rendering twenty members with names meant twenty calls to
 * {@code GET /api/v1/admin/users/{personId}} — an N+1 the client paid, which is the worst place to
 * pay one because it is also the only place nobody can profile. The fix is a sideload
 * ({@code ?include=person}), and a sideload is only a fix if the server side of it is a single
 * batched read; a loop behind a compound document is the same N+1 with a nicer envelope.
 *
 * <h2>Why the port is in {@code shared} and not in {@code identity}</h2>
 *
 * <p>{@code identity} already publishes {@code PersonDirectory} — {@code findPersonIdByEmail},
 * {@code emailsByPersonIds}, {@code linkedAccounts} — and a fourth method there would have been the
 * shorter diff. It is the wrong home for one reason that outlives the diff: <b>{@code person} is
 * platform-only forever</b> (ADR 0010 §2.2, restated as settled in ADR 0011 §3), and this is the read
 * a tenant serving its own member list must still answer when it is no longer in the same deployment
 * as the person graph. A consumer compiled against {@code identity.PersonDirectory} is compiled
 * against a module that is definitionally not there; a consumer compiled against a {@code shared}
 * port gets its implementation swapped for a wire adapter and does not notice, which is exactly what
 * {@link ug.co.smsone.shared.security.PersonLookup} and
 * {@link ug.co.smsone.shared.security.OrgLookup} are already shaped for. ADR 0011 §2.1 lists the
 * read paths that cross the split with a staleness contract each; every one of them is a
 * {@code shared} port, and this is another.
 *
 * <p>It sits in {@code shared.directory} rather than beside those two in {@code shared.security}
 * deliberately. A name and an address are a rendering, never an authorization fact, and a port filed
 * under {@code security} is one somebody eventually reads a permission out of.
 *
 * <h2>How this relates to {@code compliance.internal.PersonProjector} — one concept, two moments</h2>
 *
 * <p>They project the SAME thing, defined by the same paragraph, and neither is a copy of the other:
 *
 * <ul>
 *   <li>{@code PersonProjector} is the <b>bundle writer</b>. It derives WHICH people from the
 *       catalogue (every {@code person_id} / {@code created_by} column in the tenant schema), runs
 *       once per extraction, and emits raw column tuples — {@code version}, {@code created_at},
 *       {@code invited_at} included — because those rows have to be INSERTABLE on the far side.</li>
 *   <li>This port is the <b>request-path reader</b>. The caller already knows which people; nothing
 *       is derived, nothing is restorable, and the bookkeeping columns are absent because no API
 *       renders them.</li>
 * </ul>
 *
 * <p>The invariant they share is the COLUMN SET, and it is stated once, in {@link PersonProjection}.
 * Widening this port without widening §2.2 — or vice versa — is the change that makes an extracted
 * tenant's member list disagree with the bundle it was built from.
 *
 * <h2>Absent implementation is empty, not denied</h2>
 *
 * <p>Consumers resolve this through {@code ObjectProvider} and fall back to no names. That is the
 * opposite posture from {@link ug.co.smsone.shared.security.OrgAuthorization}, and the difference is
 * the point: an unanswerable authorization question must deny, an unanswerable rendering question
 * must degrade. A member list with ids and no names is the surface as it stood before this port
 * existed; a member list that 500s because the directory is unreachable is a worse answer than the
 * one it replaced.
 *
 * <h2>Not cached today, and where the cache goes when it is</h2>
 *
 * <p>ADR 0011 §3 says the projection is "cached under §2's contract", and that contract —
 * stale-while-unreachable, PT15M per-entry ceiling, throw at the ceiling — is a property of the
 * <b>remote</b> read. In one deployment this is two indexed selects against the same database that
 * already served the membership rows, so a cache would buy nothing and cost the one thing a member
 * list must not get wrong: a person renamed a second ago rendering under their old name, per pod,
 * for a TTL. The cache belongs with the transport that needs it (Phase 8's wire adapter), keyed per
 * person so a page of twenty is twenty entries and not one entry per set of twenty — which is the
 * trap a {@code @Cacheable} on this method's argument would walk straight into.
 */
public interface PersonProjections {

    /**
     * The projection of every id in {@code personIds} that names a live person.
     *
     * <p><b>Absent means absent, and the caller must render it.</b> An id with no entry in the
     * returned map is a person who was soft-deleted, or one who never existed; both are the same
     * answer here on purpose, because a membership row naming an erased human is a fact about the
     * roster and hiding it would make the row unexplainable. The caller shows the id.
     *
     * <p>A {@link Set} rather than a {@link java.util.List}, so deduplication is structural rather
     * than a step each caller has to remember: a compound document that lists the same person twice
     * is malformed, and the shape that stops it should be the argument type, not a code review.
     *
     * <p><b>Bounded input.</b> Implementations read in batches, so this stays a fixed small number of
     * statements however many ids arrive — but the result is materialised in memory, so this is for a
     * page of members, not for a tenant's whole roster. Never call it with an unbounded set.
     *
     * @param personIds may be empty or null; both answer an empty map without touching a database
     */
    Map<UUID, PersonProjection> projectionsOf(Set<UUID> personIds);
}
