package ug.co.smsone.shared.retention;

/**
 * The org-scoped retention logs that honor per-org overrides. Notification deliveries are
 * deliberately NOT here: they are keyed by recipient (email / phone / user id), not by org, so a
 * per-org retention has no meaning for them.
 */
public final class RetentionScope {

    /** The webhook delivery log (`webhook_delivery.org_id`). */
    public static final String WEBHOOK_DELIVERY = "WEBHOOK_DELIVERY";

    /** The exchange job log (`exchange_job.org_id`; a null org is a platform-scoped handler). */
    public static final String EXCHANGE_JOB = "EXCHANGE_JOB";

    private RetentionScope() {
    }
}
