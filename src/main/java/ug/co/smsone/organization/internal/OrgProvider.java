package ug.co.smsone.organization.internal;

/**
 * Who minted the identifier in an {@link ExternalOrganization} row.
 *
 * <p>The column is a {@code varchar} sharing {@code external_identity.provider}'s vocabulary, not a
 * Postgres enum — adding a provider stays an INSERT. That the vocabulary is shared is a fact about the
 * STRINGS, not about the Java types: identity's own enum is package-private inside its module, and
 * importing it would make this module compile-depend on another module's internals to spell one word.
 * Two small enums over one shared vocabulary is the cheaper of the two couplings.
 *
 * <p>{@code PASSKEY} and {@code API_KEY} are deliberately absent: a passkey and a machine credential
 * authenticate a person, they cannot name a tenant, so there is nothing they could mint an
 * organization id with.
 */
enum OrgProvider {

    KEYCLOAK,
    GOOGLE,
    MICROSOFT,
    APPLE,
    SAML,
    LDAP,
    INTERNAL
}
