package ug.co.smsone.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for turning a validated token into a {@code person.id}, implemented by the module that owns
 * {@code external_identity}. The edge holds the only {@code iss}/{@code sub} pair in the system and
 * needs a person; the table that maps one to the other lives in {@code identity} — so the interface
 * lives here and the implementation there, the same seam as {@link OrgAuthorization} and
 * {@link ImpersonationLookup}, and the reason {@code shared} never compile-depends on {@code identity}.
 *
 * <p>With no implementation present every token resolves to no person. Nothing default-grants: a caller
 * with no person id holds no org permissions and is refused by the provisioning gate.
 *
 * <h2>The staleness contract (ADR 0011 §2) — Phase 8's wire adapter implements it unchanged</h2>
 *
 * <p>This read is <b>stale-while-unreachable</b>. When the authority behind it cannot be ASKED — a
 * connection-shaped failure: the borrow timing out, connection refused, DNS, a statement dying at the
 * socket (never an answer we dislike; a query-shaped error is a bug and rethrows) — the implementation
 * serves the last answer it confirmed, per entry, up to the hard ceiling
 * {@code app.tenancy.identity-stale.ceiling} (PT15M) measured from that entry's own last successful
 * refresh — not from the start of the outage. Two consequences are load-bearing:
 *
 * <ul>
 *   <li><b>An authoritative "absent" is an answer and replaces the entry immediately</b> — the stale
 *       path never resurrects an erased identity; it can only repeat the erasure.</li>
 *   <li><b>At the ceiling the method THROWS</b> ({@code shared.cache.PlatformUnreachableException},
 *       503 + Retry-After on the wire) rather than answering empty: empty flows into the provisioning
 *       gate as {@code ACCOUNT_NOT_PROVISIONED}, and telling a paying user they don't exist because a
 *       database is down is a support incident with a wrong root cause attached.</li>
 * </ul>
 *
 * <p>When the JPA implementation becomes an HTTP one (ADR 0011 §9), "unreachable" changes meaning from
 * "the primary database is down" to "the platform service is down" — and nothing else changes: same
 * ceiling, same per-entry measurement, same throw, same status code. The contract lives here so the
 * transport swap is one class, not a renegotiation.
 */
public interface PersonLookup {

    /**
     * The person this issuer's subject belongs to, or empty when nothing is linked to it.
     *
     * <p>Empty is a complete answer, not an error. A validated token proves who an issuer thinks you
     * are; it has never been permission to create an account (AGENTS §1 — no JIT provisioning), so an
     * unknown subject resolves to nothing and the provisioning gate decides what the caller is told.
     *
     * <p><b>No provider argument, deliberately.</b> The edge knows the issuer that signed the token and
     * the subject inside it; which vocabulary word — {@code KEYCLOAK}, {@code GOOGLE}, {@code SAML} —
     * names that issuer is the implementation's business, because deciding it here is exactly the code
     * change that adding a second identity provider must not require. The issuer is what makes a subject
     * unique (V10: a staging realm and a prod realm both emit {@code KEYCLOAK} over disjoint subject
     * spaces), so both arguments are always required and neither may be blank.
     */
    Optional<UUID> personId(String issuer, String externalSubject);
}
