package ug.co.smsone.geo.internal;

import java.util.List;
import java.util.UUID;
import ug.co.smsone.shared.geo.GeoStamp;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * The spatial-query seam. EVERY coordinate-aware read goes through here, so nothing above the
 * persistence layer knows how geo is stored. Phase 1 impl {@link HaversineGeoSearch} filters by a
 * bounding box over B-tree indexes; a later {@code PostgisGeoSearch} (ST_DWithin/GiST) can replace it
 * with no change to callers — the reason coordinates never leave this package as anything but decimals.
 */
interface GeoSearch {

    /** Run a filtered, keyset-paginated search (captured_at desc, id desc). */
    Page search(Query query, CursorPageRequest page);

    /**
     * Filters, all optional except {@code orgId}. A bounding box applies only when all four corners are
     * present; a null subject axis is unconstrained.
     */
    record Query(UUID orgId, String subjectType, String subjectId,
            Double minLat, Double minLng, Double maxLat, Double maxLng) {

        boolean hasBoundingBox() {
            return minLat != null && minLng != null && maxLat != null && maxLng != null;
        }
    }

    /** One page of stamps plus the keyset cursor for the next (null when the last page). */
    record Page(List<GeoStamp> stamps, boolean hasMore, String nextCursor) {
    }
}
