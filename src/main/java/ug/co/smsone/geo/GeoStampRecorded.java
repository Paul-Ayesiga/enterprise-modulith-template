package ug.co.smsone.geo;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a geo stamp is stored. Consumed internally to resolve the place asynchronously (so
 * capture never waits on a geocoder), and available to any module that wants to react to a location
 * being recorded. {@code occurredAt} is the dedup key for idempotent consumers — see docs/EVENTS.md.
 *
 * <p>{@code capturedByPersonId} is {@code person.id} and is null for an unauthenticated OR a machine
 * capture. {@code subjectId} deliberately stays a String: it is polymorphic (V47) and points at any
 * record type, so it holds a person key only on the rows whose {@code subjectType} is person-shaped.
 */
public record GeoStampRecorded(UUID orgId, UUID stampId, String subjectType, String subjectId,
        UUID capturedByPersonId, Instant occurredAt) {
}
