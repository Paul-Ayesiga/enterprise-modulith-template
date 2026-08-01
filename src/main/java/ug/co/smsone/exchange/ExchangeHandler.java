package ug.co.smsone.exchange;

import java.util.List;
import java.util.Map;

/**
 * The domain seam: a business module plugs its aggregate into the exchange platform by
 * implementing this and keeping ALL business rules on its own side — the platform never learns
 * what a "member" is. Contracts the platform relies on:
 *
 * <ul>
 *   <li>{@link #importRecord} is IDEMPOTENT per the record's natural key. Delivery is
 *       at-least-once: a resumed job replays the records of its last uncommitted batch.</li>
 *   <li>A data problem throws {@link InvalidRecordException} — reported row-addressed, never
 *       retried. Anything else is infrastructure: the batch fails and the job retries.</li>
 *   <li>Authorization is two-layered: the platform checks {@link #importPermission()} /
 *       {@link #exportPermission()} for the submitter at submit time; the handler re-checks
 *       anything grant-shaped per record against the REQUESTER'S permissions at processing time
 *       (revocation-safe — the members handler's escalation guard is the model).</li>
 * </ul>
 */
public interface ExchangeHandler {

    /** Stable id clients submit with (e.g. {@code org-members}). */
    String id();

    /** The org permission code an import submitter must hold (submit-time gate). */
    String importPermission();

    /** The org permission code an export submitter must hold — reading out is not granting in. */
    String exportPermission();

    /** The record shape: column order for CSV, key set for JSONL — also the downloadable template. */
    List<String> header();

    /** Apply one import record. Idempotent; throws {@link InvalidRecordException} on data problems. */
    ImportOutcome importRecord(ExchangeContext context, Map<String, String> record);

    /** Stream the aggregate out, one record at a time — push-style so the handler owns its resources. */
    void export(ExchangeContext context, RecordWriter out);
}
