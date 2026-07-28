package ug.co.smsone.identity;

import java.time.Instant;

/** Published when a user is first provisioned (INVITED). Consumers may send a welcome/onboarding message. */
public record UserProvisioned(String subject, String email, Instant occurredAt) {
}
