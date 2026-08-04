package ug.co.smsone.shared.geo;

import java.time.Instant;

/**
 * A single positional reading submitted for attachment — the raw fact a device (or the edge) reports.
 * Latitude/longitude are plain decimal degrees (WGS-84): the domain never speaks in spatial types, so
 * the persistence layer can later move to PostGIS without touching a caller. {@code capturedBy} is not
 * here on purpose — the attach impl fills <em>who</em> from the security context, exactly as the audit
 * log does, so no call site can misstate it.
 *
 * @param latitude    decimal degrees, [-90, 90]
 * @param longitude   decimal degrees, [-180, 180]
 * @param accuracyM   reported horizontal accuracy in metres, or {@code null} if unknown
 * @param altitudeM   metres above the ellipsoid, or {@code null}
 * @param source      how the fix was obtained
 * @param capturedAt  the device's fix time (not the server receive time)
 * @param consentRef  reference to the consent under which this was captured, when policy requires it
 */
public record GeoFix(double latitude, double longitude, Double accuracyM, Double altitudeM,
        GeoSource source, Instant capturedAt, String consentRef) {

    public GeoFix {
        if (source == null) {
            throw new IllegalArgumentException("A GeoFix requires a source.");
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("A GeoFix requires capturedAt.");
        }
    }

    /** Whether the coordinate is within the valid WGS-84 range (validation reports the specific field). */
    public boolean hasValidCoordinates() {
        return latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }
}
