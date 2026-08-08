package ug.co.smsone.shared.tenancy.promotion;

import java.io.Serial;

/**
 * A promotion or demotion did not complete. The message is written for the operator who is holding the
 * runbook open, so it says what state the tenant is in now — not just what failed.
 *
 * <p>Unchecked and deliberately not an {@code ApiException}: promotion is operator-initiated from a
 * runbook (ADR 0010 §8 Q3), so there is no HTTP surface to shape an error code for, and giving it one
 * would suggest there is a route that triggers this.
 */
public class TenantPromotionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TenantPromotionException(String message) {
        super(message);
    }

    public TenantPromotionException(String message, Throwable cause) {
        super(message, cause);
    }
}
