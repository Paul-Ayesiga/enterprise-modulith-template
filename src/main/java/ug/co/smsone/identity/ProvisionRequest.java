package ug.co.smsone.identity;

/** What an admin supplies to provision a user. The email becomes the Keycloak username. */
public record ProvisionRequest(String email, String firstName, String lastName) {
}
