package ug.co.smsone.identity;

import java.time.Instant;
import java.util.UUID;

/** Published the first time a provisioned person reaches the API ({@code INVITED → ACTIVE}). */
public record PersonActivated(UUID personId, Instant occurredAt) {
}
