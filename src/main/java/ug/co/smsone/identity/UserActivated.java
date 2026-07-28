package ug.co.smsone.identity;

import java.time.Instant;

/** Published the first time a provisioned user reaches the API (INVITED → ACTIVE). */
public record UserActivated(String subject, Instant occurredAt) {
}
