package ug.co.smsone.settings;

import java.time.Instant;

/**
 * Published (via the DB-backed registry) whenever a setting is created or updated.
 * {@code occurredAt} joined late (every sibling event always carried it): it is what lets a future
 * idempotent consumer dedupe a redelivery of the SAME change while still reacting to a genuine
 * later re-set to the same value — the {@code FeatureFlagChanged} rule, now uniform.
 */
public record SettingChanged(String key, String value, Instant occurredAt) {
}
