package ug.co.smsone.identity;

/** What an admin supplies to provision a user. The email becomes the Keycloak username. */
public record ProvisionRequest(String email, String firstName, String lastName, boolean requireTotp) {

    /** Convenience for the common case: no TOTP enrollment demanded by an org policy. */
    public ProvisionRequest(String email, String firstName, String lastName) {
        this(email, firstName, lastName, false);
    }
}
