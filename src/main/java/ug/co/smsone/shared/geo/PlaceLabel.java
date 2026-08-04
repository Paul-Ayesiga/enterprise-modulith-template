package ug.co.smsone.shared.geo;

/**
 * A human-readable place derived from a coordinate — <em>enrichment</em>, never the source of truth.
 * Every field is nullable: reverse geocoding may be disabled, pending (resolved asynchronously), or
 * only able to resolve a coarse level (country/region without a street). The coordinate remains the
 * fact; this is regenerable metadata.
 */
public record PlaceLabel(String countryCode, String admin1, String locality, String formattedAddress) {

    /** True when nothing has been resolved yet (all fields null) — e.g. geocoding is off or pending. */
    public boolean isEmpty() {
        return countryCode == null && admin1 == null && locality == null && formattedAddress == null;
    }
}
