package ug.co.smsone.shared.tenancy.placement;

/**
 * What condition a tenant's home is in — the {@code state} column of {@code platform.tenant_placement}
 * (ADR 0010 §4.2), and the only thing that licenses serving a tenant at all.
 *
 * <p><strong>{@link #ACTIVE} does double duty, and the second job is the one worth reading.</strong>
 * It means the schema is fit to serve, and it also means <em>this tenant has been announced</em> —
 * {@code OrganizationRegistered} has been published for it. Those are the same fact stated once,
 * because ADR 0010 §4.3's rule is that a tenant is never announced before its schema can serve it: if
 * they could disagree, one of them would be the lie. Holding them in one column is what lets the
 * announcement be decided by a single conditional {@code UPDATE} instead of by "did Hibernate insert a
 * row just now", which is a question nobody can ask again after a crash.
 *
 * <p>The values are persisted verbatim and constrained by {@code tenant_placement_state_known}. A
 * fourth (Phase 5's {@code PROMOTING} is the candidate) is an expand step in its own release — see
 * V57's note, and AGENTS §4.6.
 */
public enum PlacementState {

    /**
     * A home has been claimed and is not yet proven fit. Written <em>before</em> any DDL runs, so a
     * process killed mid-provision leaves this rather than leaving nothing — and a row saying
     * PROVISIONING for the last twenty minutes is a queryable fact, where an absent row is
     * indistinguishable from a tenant nobody ever tried to create.
     *
     * <p>It is also the state that means NOT YET ANNOUNCED. Nothing downstream of the tenant — no
     * trial, no billing account, no search document — exists yet, by design.
     */
    PROVISIONING,

    /**
     * The schema exists, is at {@code schema_version}, and the tenant has been announced. The only
     * state that licenses serving a request.
     */
    ACTIVE,

    /**
     * Provisioning or migration was attempted and did not finish; {@code last_error} says why. The
     * tenant is not half-made — tenant migrations run inside a transaction, so the schema sits at a
     * whole version (ADR 0010 §4.2) — it is simply not finished, and a retry resumes from here.
     */
    FAILED;

    /** Parses the column. An unknown value is a constraint that was widened without its enum. */
    static PlacementState of(String value) {
        for (PlacementState state : values()) {
            if (state.name().equals(value)) {
                return state;
            }
        }
        throw new IllegalStateException(
                "platform.tenant_placement.state holds '" + value + "', which no PlacementState names —"
                        + " the check constraint was widened without widening this enum (ADR 0010 §4.2)");
    }
}
