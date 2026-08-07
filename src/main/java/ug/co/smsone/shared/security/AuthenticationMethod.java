package ug.co.smsone.shared.security;

/**
 * How the caller proved who they are. This is the axis that tells a PERSON from a MACHINE, which is
 * the job {@code "key:" + keyId} used to do by shaping a machine credential like a subject.
 *
 * <p>A null {@link CurrentUser#personId()} means two completely different things depending on this
 * value, and that is exactly why it is carried:
 *
 * <ul>
 *   <li>{@link #API_KEY} + no person — normal and permanent. An org key belongs to an organization,
 *       not to anybody; {@code external_identity.API_KEY} is reserved and unused (V10) precisely so
 *       no synthetic person is minted for a robot.
 *   <li>{@link #OIDC} + no person — a validated token for a subject with no {@code external_identity}
 *       row: somebody who signed in but has not been provisioned. A valid token is not access
 *       (AGENTS §1, no JIT provisioning), so the provisioning gate answers this one, not the edge.
 * </ul>
 *
 * <p>Adding a second identity provider does NOT add a value here. Google, SAML or LDAP tokens are
 * still {@link #OIDC} at the edge and still resolve through {@code external_identity} — the provider
 * is a column over there, never a branch in this package.
 */
public enum AuthenticationMethod {

    /** A bearer token from a trusted issuer — the web app's login token or an MCP consent token. */
    OIDC,

    /** An {@code sk_} machine credential ({@code X-Api-Key}, or the same value as a bearer). */
    API_KEY,

    /**
     * An operator's OIDC token wearing another person's identity through an impersonation session.
     * The effective identity is the target's; {@link CurrentUser#impersonation()} names the operator,
     * and the authority collection is empty (AGENTS §1 — impersonation carries no authority).
     */
    IMPERSONATION
}
