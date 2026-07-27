package ug.co.smsone.settings;

/** Published when a feature flag is created or toggled. */
public record FeatureFlagChanged(String key, boolean enabled) {
}
