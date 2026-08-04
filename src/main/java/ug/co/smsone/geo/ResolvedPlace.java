package ug.co.smsone.geo;

/**
 * A place a {@link Geocoder} resolved from a coordinate — the SPI's output. Richer than
 * {@code shared.geo.PlaceLabel} (it may carry a provider-specific {@code placeId} and names the
 * {@code provider}); the module maps it onto the stored stamp's place columns and the coarser
 * {@code PlaceLabel} it hands back through the shared port.
 */
public record ResolvedPlace(String countryCode, String admin1, String locality,
        String formattedAddress, String placeId, String provider) {
}
