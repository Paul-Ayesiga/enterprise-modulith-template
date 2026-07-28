package ug.co.smsone.identity;

/** Lifecycle of a provisioned user. Only {@code ACTIVE} users may act; {@code INVITED} may read {@code /me}. */
public enum ProvisioningStatus {
    /** Admin-provisioned; Keycloak account exists but the user hasn't completed first-login setup. */
    INVITED,
    /** Completed first login (set their password) and hit the API — full access. */
    ACTIVE,
    /** Access revoked. */
    DISABLED
}
