package ug.co.smsone.geo;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a geo stamp is stored. Consumed internally to resolve the place asynchronously (so
 * capture never waits on a geocoder), and available to any module that wants to react to a location
 * being recorded. {@code occurredAt} is the dedup key for idempotent consumers — see docs/EVENTS.md.
 */
public record GeoStampRecorded(UUID orgId, UUID stampId, String subjectType, String subjectId,
        String capturedBy, Instant occurredAt) {
}
