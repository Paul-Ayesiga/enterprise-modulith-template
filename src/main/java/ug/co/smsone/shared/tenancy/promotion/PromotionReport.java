package ug.co.smsone.shared.tenancy.promotion;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ug.co.smsone.shared.tenancy.promotion.TenantRows.Fingerprint;

/**
 * What one completed move did, table by table — the record the runbook tells an operator to keep, and
 * the thing a demotion is checked against.
 *
 * <p>ADR 0010 §7 Phase 5's gate is "<em>a real org promoted to {@code t_<hex>} and demoted back, with
 * per-table row counts identical at both ends and a measured freeze window recorded in the runbook</em>".
 * Both halves of that sentence are fields here: {@link #verified} carries the per-table counts AND
 * checksums that were compared, and {@link #freezeHeld} is the measurement — taken from the clock, over
 * the real work, rather than estimated afterwards from a log.
 *
 * @param orgId the tenant that moved
 * @param from the schema it left
 * @param to the schema it now serves from
 * @param verified per table, the fingerprint that was found IDENTICAL on both sides. A report exists
 *     only for a move that verified, so this is the proof and not merely a summary
 * @param removedFromSource per table, the rows deleted from {@code from} once the flip had happened.
 *     Compared against {@link #verified} by the promoter; a difference means a writer got past the
 *     freeze and is reported rather than swallowed
 * @param freezeHeld how long the tenant's writes were blocked, end to end — the number the runbook asks
 *     for and the only honest input to sizing the next promotion's budget
 * @param warnings anything that did not stop the move but that somebody has to act on — a cache
 *     eviction that failed, a source delete that found more rows than were copied. Empty on a clean run
 */
public record PromotionReport(
        UUID orgId,
        String from,
        String to,
        Map<String, Fingerprint> verified,
        Map<String, Long> removedFromSource,
        Duration freezeHeld,
        List<String> warnings) {

    public PromotionReport {
        verified = Map.copyOf(verified);
        removedFromSource = Map.copyOf(removedFromSource);
        warnings = List.copyOf(warnings);
    }

    /** Every row that moved, across every table. The headline number for the runbook's log. */
    public long rowsMoved() {
        return verified.values().stream().mapToLong(Fingerprint::rows).sum();
    }

    /** The tables that actually carried rows — the short list worth reading in a terminal. */
    public Map<String, Fingerprint> nonEmpty() {
        return verified.entrySet().stream()
                .filter(entry -> entry.getValue().rows() > 0)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first, java.util.LinkedHashMap::new));
    }
}
