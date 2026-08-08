package ug.co.smsone.shared.tenancy.promotion;

import java.io.Serial;
import java.util.UUID;

/**
 * A write was attempted against a tenant whose rows are being moved (ADR 0010 §6 hop 0→1).
 *
 * <p>Unchecked, and it names the tenant rather than the table, because the caller cannot fix this by
 * writing somewhere else — the whole tenant is off limits for the length of the freeze. A job that can
 * come back later should ask {@link TenantFreezes#isFrozen} and skip the tenant on this pass instead of
 * catching this.
 */
public class TenantFrozenException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID orgId;

    TenantFrozenException(UUID orgId) {
        super("Organization " + orgId + " is frozen for a tenant promotion (ADR 0010 §6 hop 0->1): its"
                + " rows are being copied between schemas, so a write now would either land in the schema"
                + " the tenant is leaving or race the verification that decides whether the copy is"
                + " correct. Background work must skip this tenant until the freeze lifts —"
                + " `select * from platform.tenant_freeze where org_id = '" + orgId + "'` says who holds"
                + " it and when it lapses on its own.");
        this.orgId = orgId;
    }

    /** The tenant that may not be written. */
    public UUID orgId() {
        return orgId;
    }
}
