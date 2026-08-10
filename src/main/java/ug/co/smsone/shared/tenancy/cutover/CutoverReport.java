package ug.co.smsone.shared.tenancy.cutover;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ug.co.smsone.shared.tenancy.promotion.TenantRows.Fingerprint;

/**
 * What one completed cutover (or rollback) did — the {@code PromotionReport} shape with the schema
 * axis swapped for the datasource axis, because the claim ADR 0011 §7.2 makes is measurable and this
 * is where the measurement lives: <em>a freeze measured in seconds, independent of tenant size</em>.
 *
 * @param orgId the tenant that moved
 * @param schemaName the silo — unchanged by a cutover; only where it is hosted changes
 * @param fromDatasource where the tenant was served from when the move began
 * @param toDatasource where it is served from now
 * @param verified per table, the fingerprint found IDENTICAL on both databases before the flip — the
 *     cross-database copy's proof, not a summary of it
 * @param syncing how long the online phase ran: destination build, initial copy, catch-up. The half
 *     that scales with tenant size and costs nobody anything
 * @param freezeHeld how long writes were actually blocked, end to end — the number the §7.2 claim is
 *     asserted against, taken from the clock over the real work
 * @param warnings anything that did not stop the move but that somebody must act on — a cache that
 *     would not evict, a stale source copy left for {@code reclaimAbandonedCopy}. Empty on a clean run
 */
public record CutoverReport(
        UUID orgId,
        String schemaName,
        String fromDatasource,
        String toDatasource,
        Map<String, Fingerprint> verified,
        Duration syncing,
        Duration freezeHeld,
        List<String> warnings) {

    public CutoverReport {
        verified = Map.copyOf(verified);
        warnings = List.copyOf(warnings);
    }

    /** Every row that moved databases, across every table. */
    public long rowsMoved() {
        return verified.values().stream().mapToLong(Fingerprint::rows).sum();
    }

    /** The tables that actually carried rows — the short list worth reading in a terminal. */
    public Map<String, Fingerprint> nonEmpty() {
        return verified.entrySet().stream()
                .filter(entry -> entry.getValue().rows() > 0)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first, LinkedHashMap::new));
    }
}
