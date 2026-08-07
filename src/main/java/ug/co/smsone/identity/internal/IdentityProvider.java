package ug.co.smsone.identity.internal;

/**
 * Who minted the identifier in an {@link ExternalIdentity} row.
 *
 * <p>The column is a {@code varchar} with this vocabulary, not a Postgres enum, precisely so adding a
 * provider stays an INSERT rather than an {@code alter type … add value} on the migration path. This
 * enum is the Java half of that vocabulary; extending it is a code change and nothing more.
 *
 * <p>{@code API_KEY} is reserved for PERSONAL access tokens and <b>no such row is written today</b>.
 * {@code api_key} (V29) has an org and a platform tier but no owner, so every key belongs to an
 * organization or to the platform and is not any person. Manufacturing a synthetic person per machine
 * key would be actively harmful: it would flow into {@code created_by}, be eligible for a membership,
 * be scanned by the reconciliation job, and be a valid subject for an erasure request against a robot.
 */
enum IdentityProvider {

    KEYCLOAK,
    GOOGLE,
    MICROSOFT,
    APPLE,
    PASSKEY,
    SAML,
    LDAP,
    /** Reserved for personal access tokens. Unused until {@code api_key} gains an owner column. */
    API_KEY,
    INTERNAL
}
