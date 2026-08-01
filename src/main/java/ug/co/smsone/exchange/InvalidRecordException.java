package ug.co.smsone.exchange;

/**
 * A DATA problem with one record: reported row-addressed in the job's error report and never
 * retried. The message is written into a tenant-downloadable report, so it must be curated —
 * actionable, no internals. Infrastructure failures must NOT use this type: throw anything else
 * and the batch fails, the job reclaims and retries.
 */
public class InvalidRecordException extends RuntimeException {

    public InvalidRecordException(String message) {
        super(message);
    }
}
