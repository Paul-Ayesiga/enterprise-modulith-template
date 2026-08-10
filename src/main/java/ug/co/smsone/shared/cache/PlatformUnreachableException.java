package ug.co.smsone.shared.cache;

import java.io.Serial;
import ug.co.smsone.shared.error.ApiException;
import ug.co.smsone.shared.error.ErrorCode;

/**
 * The platform authority could not be asked AND nothing is entitled to answer for it — thrown by
 * {@link StaleWhileUnreachable} at the staleness ceiling (ADR 0011 §2.3), and by the one read that has
 * no ceiling because it has no grace: {@code PermissionResolver}'s org-status half, which must refuse
 * rather than serve authorization off a platform fact nobody could confirm (§2.1's never-stale row).
 *
 * <p><b>The status is the contract, not a convenience.</b> 503 + {@code Retry-After} in the envelope —
 * the {@code TenantSchemaFloor} shape — because it makes the same claim: <em>this system cannot
 * currently answer for you; try again</em>. Never 403 ("you are known and refused" — false), never 401
 * ("your token is bad" — false), and above all never {@code ACCOUNT_NOT_PROVISIONED}: telling a paying
 * user they don't exist because a database is down is a support incident with a wrong root cause
 * attached. {@code CurrentUserFilter} renders it on tenant-scoped routes (a throw from a filter would
 * bypass {@code GlobalExceptionHandler}); the handler renders it everywhere else and both set the
 * {@code Retry-After} header from {@link #retryAfterSeconds()}.
 */
public class PlatformUnreachableException extends ApiException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * When the caller should ask again. The end of the outage is unknowable from here, so this is the
     * polling idiom {@code TenantSchemaFloor.RECHECK_AFTER} set: a client that obeys the header arrives
     * to a fresh authoritative probe rather than to a remembered refusal — nothing here memoizes the
     * denial, so every retry re-asks the database.
     */
    private static final long RETRY_AFTER_SECONDS = 30;

    public PlatformUnreachableException(String detail) {
        super(ErrorCode.SERVICE_UNAVAILABLE, detail);
    }

    public long retryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }
}
