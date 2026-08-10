package ug.co.smsone.shared.tenancy.cutover;

/**
 * The two libpq connection strings a cutover's subscriptions dial — and the reason they are
 * <strong>arguments, not derivations</strong>: a subscription's {@code CONNECTION} is dialled by the
 * <em>database server</em> on the other side, not by this JVM, so the host that is right in a JDBC URL
 * here (a localhost port-forward, a Docker-published port) can name nothing from where the subscriber
 * runs. Only the operator knows both networks, which suits ADR 0011 §10 Q6 — cutovers are
 * operator-initiated from a runbook, and the runbook shows how to spell these.
 *
 * @param sourceConninfo how the TARGET database server reaches the SOURCE — the forward subscription's
 *     {@code CONNECTION} (e.g. {@code host=pg-primary port=5432 dbname=app user=repl password=…}).
 *     The user needs {@code REPLICATION} (or superuser) on the source.
 * @param targetConninfo how the SOURCE database server reaches the TARGET — the reverse subscription's
 *     {@code CONNECTION}, created at §7.2 step 9 while the freeze still holds
 */
public record CutoverLinks(String sourceConninfo, String targetConninfo) {

    public CutoverLinks {
        if (sourceConninfo == null || sourceConninfo.isBlank()
                || targetConninfo == null || targetConninfo.isBlank()) {
            throw new IllegalArgumentException("a cutover needs both connection strings up front: the"
                    + " reverse subscription is created INSIDE the freeze window (ADR 0011 §7.2 step 9),"
                    + " which is the wrong moment to discover the second one is missing");
        }
    }

    /**
     * Conninfo strings routinely carry {@code password=…}. They go into {@code CREATE SUBSCRIPTION}
     * and nowhere else — never a log line, never an exception message (AGENTS §9).
     */
    @Override
    public String toString() {
        return "CutoverLinks[redacted]";
    }
}
