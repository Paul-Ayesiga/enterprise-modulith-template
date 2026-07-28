package ug.co.smsone.identity;

/**
 * Result of provisioning. {@code subject} is the Keycloak user id (the JWT {@code sub});
 * {@code alreadyExisted} is true when the Keycloak user was reused rather than created.
 */
public record ProvisionedUser(String subject, String email, boolean alreadyExisted) {
}
