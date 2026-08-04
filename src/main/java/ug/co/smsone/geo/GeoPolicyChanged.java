package ug.co.smsone.geo;

import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.geo.CaptureMode;

/**
 * Published when an org's capture policy for a record-type changes (created, mode changed, or removed).
 * {@code occurredAt} is the dedup key — see docs/EVENTS.md.
 */
public record GeoPolicyChanged(UUID orgId, String subjectType, CaptureMode mode, Instant occurredAt) {
}
