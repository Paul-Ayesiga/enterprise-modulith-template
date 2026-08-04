/**
 * Geolocation — capture a device/edge position, attach it to any record via the shared
 * {@link ug.co.smsone.shared.geo.GeoStamps} port, enforce a per-record-type capture policy, and enrich
 * coordinates into human-readable places through a pluggable {@link ug.co.smsone.geo.Geocoder} SPI.
 *
 * <p>Coordinates (plain decimal degrees) are the source of truth; a resolved place is regenerable
 * enrichment. All spatial operations are confined to the module's persistence layer (bounding-box +
 * Haversine over B-tree indexes today), so a later move to PostGIS is a persistence-only change and no
 * caller ever sees a spatial type.
 */
@ApplicationModule(displayName = "Geolocation")
package ug.co.smsone.geo;

import org.springframework.modulith.ApplicationModule;
