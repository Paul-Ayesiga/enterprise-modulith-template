package ug.co.smsone.geo;

import java.util.Optional;

/**
 * The reverse-geocoding SPI: turn a coordinate into a {@link ResolvedPlace}. Published (not
 * {@code internal}) so a deployment can register its OWN implementation as a Spring bean — the
 * {@code CUSTOM} provider — without importing module internals. The built-in adapters (disabled,
 * offline dataset, self-hosted Nominatim, external cloud provider) are selected by the
 * {@code geo.geocoder.provider} property; a deployment-supplied bean overrides them.
 *
 * <p>Contract: never throws for a not-found (returns {@link Optional#empty()}); results are cached by
 * the module, so an implementation need not cache itself; and it MUST NOT assume it runs on the request
 * thread — the module invokes it asynchronously after a stamp is recorded, so capture latency never
 * waits on a provider.
 */
public interface Geocoder {

    /** Provider id stored with a resolved place and used as the cache key's namespace (e.g. {@code offline}). */
    String name();

    /** Resolve a place for the coordinate, or empty when nothing matches or geocoding is disabled. */
    Optional<ResolvedPlace> reverse(double latitude, double longitude);
}
