package ug.co.smsone.shared.geo;

/**
 * How strictly a location is captured for a given record-type, set per org in the capture policy.
 * The escalation is deliberate: an operator turns geo on ({@link #OPTIONAL}) before making it a hard
 * gate ({@link #REQUIRED}).
 */
public enum CaptureMode {
    /** No location is captured or expected; a submitted fix is ignored. */
    OFF,
    /** A location may be attached, but its absence never blocks the record. */
    OPTIONAL,
    /** A valid fix is mandatory — a missing or too-coarse one rejects the record. */
    REQUIRED
}
