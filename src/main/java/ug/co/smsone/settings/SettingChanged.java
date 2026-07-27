package ug.co.smsone.settings;

/** Published (via the DB-backed registry) whenever a setting is created or updated. */
public record SettingChanged(String key, String value) {
}
