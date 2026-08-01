package ug.co.smsone.exchange;

import java.time.Instant;
import java.util.UUID;

/**
 * An exchange job reached a terminal state. Published EXPLICITLY from the worker's terminal read
 * (the job row is the truth the event repeats — the two can never disagree), inside a small
 * transaction so the registry row commits with it. Consumers: the notification module tells the
 * requester their job finished; the webhooks module fans out {@code org.exchange.job_completed}.
 * A crash between the terminal write and this publish loses the event — the job row stays
 * authoritative and pollable, which is why the REST surface never depends on it.
 */
public record JobCompleted(UUID jobId, UUID orgId, String requester, String handler,
        String jobType, String outcome, long processed, long failed, Instant occurredAt) {
}
