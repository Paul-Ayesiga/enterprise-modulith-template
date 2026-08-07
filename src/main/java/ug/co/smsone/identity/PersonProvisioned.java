package ug.co.smsone.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a person is first provisioned ({@code INVITED}). Consumers may send a welcome or
 * onboarding message, or index the new account.
 *
 * <p>Published explicitly by the service rather than registered on the aggregate: {@code person.id} is
 * assigned when the row is persisted, so an event built in the factory would carry a null id — the
 * same exception {@code DocumentService} makes for {@code DocumentRegistered}.
 */
public record PersonProvisioned(UUID personId, String email, Instant occurredAt) {
}
