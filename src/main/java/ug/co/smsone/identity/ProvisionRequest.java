package ug.co.smsone.identity;

/**
 * What an admin supplies to provision a person. The e-mail becomes both the address we reach them at
 * and the Keycloak username.
 *
 * <p>{@code givenName} / {@code familyName}, not first/last: name ORDER is cultural, so "first" and
 * "last" name the position in one rendering rather than the part itself, and the position is wrong for
 * much of the world. Both are optional — a mononym is an ordinary name, not a malformed one — and
 * neither is ever concatenated into a display string. {@code person.formatted_name} is the display
 * value, and it is supplied by the provider or the person, never derived here.
 */
public record ProvisionRequest(String email, String givenName, String familyName, boolean requireTotp) {

    /** Convenience for the common case: no TOTP enrollment demanded by an org policy. */
    public ProvisionRequest(String email, String givenName, String familyName) {
        this(email, givenName, familyName, false);
    }
}
