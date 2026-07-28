package ug.co.smsone.identity;

/**
 * Result of provisioning. {@code subject} is the Keycloak user id (the JWT {@code sub});
 * {@code alreadyExisted} is true when a local {@code app_user} row already existed — i.e. the user
 * was already fully provisioned before this call (a bare Keycloak account with no local row counts
 * as NOT yet provisioned; the call completes it, including re-sending a lost invite).
 */
public record ProvisionedUser(String subject, String email, boolean alreadyExisted) {
}
