package ug.co.smsone.shared.tenancy.cutover;

/**
 * A cutover that could not proceed, saying exactly where the tenant is left serving from — the same
 * contract as {@code TenantPromotionException}: every message names the state the failure leaves
 * behind, because the operator reading it is mid-runbook and their next line depends on it.
 */
public class TenantCutoverException extends RuntimeException {

    public TenantCutoverException(String message) {
        super(message);
    }

    public TenantCutoverException(String message, Throwable cause) {
        super(message, cause);
    }
}
