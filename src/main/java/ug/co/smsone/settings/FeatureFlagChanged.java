package ug.co.smsone.settings;

import java.time.Instant;

/**
 * Published when a feature flag is created or toggled. {@code occurredAt} distinguishes successive
 * changes — so re-toggling a flag back to a previously-seen state still notifies — while staying
 * stable across event redelivery, letting idempotent consumers dedupe on it.
 */
public record FeatureFlagChanged(String key, boolean enabled, Instant occurredAt) {
}
