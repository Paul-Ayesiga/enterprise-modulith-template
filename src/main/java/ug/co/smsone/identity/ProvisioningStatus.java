package ug.co.smsone.identity;

/**
 * Lifecycle of a person's access. Only {@code ACTIVE} may act; {@code INVITED} may read {@code /me}.
 *
 * <p>This is the one part of the old {@code app_user} row that was never a projection of Keycloak —
 * Keycloak neither knows nor stores that a human turned up — which is why it survives the split onto
 * {@code person.status} unchanged.
 */
public enum ProvisioningStatus {
    /** Admin-provisioned; a Keycloak account is linked but the human hasn't completed first-login setup. */
    INVITED,
    /** Completed first login (set their password) and hit the API — full access. */
    ACTIVE,
    /** Access revoked. */
    DISABLED
}
