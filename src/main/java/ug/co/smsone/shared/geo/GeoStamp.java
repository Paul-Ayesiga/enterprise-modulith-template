package ug.co.smsone.shared.geo;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored geolocation stamp: a {@link GeoFix} attached to some record (identified by a polymorphic
 * {@code subjectType}/{@code subjectId} soft reference), plus who captured it and the resolved
 * {@link PlaceLabel} (which may be empty until reverse geocoding runs).
 *
 * <p>This is the read model other modules see through {@link GeoStamps}; it never carries a spatial
 * type — just decimals — so the query implementation can change underneath it.
 */
public record GeoStamp(UUID id, UUID orgId, String subjectType, String subjectId,
        double latitude, double longitude, Double accuracyM, Double altitudeM, GeoSource source,
        String capturedBy, Instant capturedAt, String consentRef, PlaceLabel place) {
}
