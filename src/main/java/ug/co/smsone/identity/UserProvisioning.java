package ug.co.smsone.identity;

/**
 * Public port for admin-driven user provisioning. Creates (or reuses) the Keycloak user, issues
 * temporary credentials, and records the local {@code app_user} row as {@code INVITED}. Idempotent:
 * re-provisioning an existing user reuses it. Called by the organization module when adding a member.
 */
public interface UserProvisioning {

    ProvisionedUser provision(ProvisionRequest request);
}
