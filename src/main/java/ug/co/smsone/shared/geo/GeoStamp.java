package ug.co.smsone.shared.geo;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored geolocation stamp: a {@link GeoFix} attached to some record (identified by a polymorphic
 * {@code subjectType}/{@code subjectId} soft reference), plus who captured it and the resolved
 * {@link PlaceLabel} (which may be empty until reverse geocoding runs).
 *
 * <p>{@code capturedByPersonId} is a {@code person.id} and is null for an unauthenticated capture or a
 * machine one — V47 says so, and null there is information rather than a missing value.
 * {@code subjectType}/{@code subjectId} stay STRINGS deliberately: the pair is polymorphic and holds a
 * person key only on the rows whose discriminator is person-shaped, so typing it would be the bug the
 * rename sweep was meant to avoid.
 *
 * <p>This is the read model other modules see through {@link GeoStamps}; it never carries a spatial
 * type — just decimals — so the query implementation can change underneath it.
 */
public record GeoStamp(UUID id, UUID orgId, String subjectType, String subjectId,
        double latitude, double longitude, Double accuracyM, Double altitudeM, GeoSource source,
        UUID capturedByPersonId, Instant capturedAt, String consentRef, PlaceLabel place) {
}
