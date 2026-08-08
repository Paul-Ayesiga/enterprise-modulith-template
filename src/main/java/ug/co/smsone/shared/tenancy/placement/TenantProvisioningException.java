package ug.co.smsone.shared.tenancy.placement;

import java.io.Serial;
import ug.co.smsone.shared.error.ApiException;
import ug.co.smsone.shared.error.ErrorCode;

/**
 * A tenant's home could not be built (ADR 0010 §4.3). The tenant exists and is <em>unannounced</em>;
 * {@code platform.tenant_placement} holds a {@link PlacementState#FAILED} row naming the cause.
 *
 * <p><strong>503 and not 500, and the reason is what a caller should do next.</strong> Nothing here is
 * the caller's fault and nothing about their request will help by being different — the retry that
 * fixes this is the same call again, once whatever broke is fixed. Provisioning is idempotent from
 * either end: the FAILED row is re-claimed rather than duplicated, and Postgres' transactional DDL
 * means the schema sits at a whole version rather than half of one.
 *
 * <p>The detail is deliberately a curated sentence. {@code ApiException} requires it and AGENTS §1
 * requires it — the real message names schemas, migration versions and database errors, and it belongs
 * in the log and in {@code last_error}, not on the wire.
 */
public class TenantProvisioningException extends ApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TenantProvisioningException(Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE,
                "This organization could not be prepared. Nothing was half-created — try again.");
        initCause(cause);
    }
}
