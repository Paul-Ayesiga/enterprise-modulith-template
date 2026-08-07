package ug.co.smsone.geo.internal;

import java.math.BigDecimal;
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
     *
     * <p>The four bounds are {@link BigDecimal} <em>because the column is</em> {@code numeric(9,6)}, and
     * that is not a style preference — it decides whether the query has an index at all. Declared as
     * {@code Double} they bind as FLOAT8, so Postgres resolves {@code numeric <= float8} by casting the
     * COLUMN, and the plan reads {@code (geo_stamp.latitude)::double precision >= …}: an expression over
     * the column, which {@code geo_stamp_bbox_idx (org_id, latitude, longitude)} cannot answer. Measured
     * on 150k stamps in one org: seq scan, 56.8&nbsp;ms, 7,379 buffers, two parallel workers conscripted
     * onto what should be an index probe (the cast also wrecks the estimate, 403 rows guessed as ~4)
     * versus 0.70&nbsp;ms and 466 buffers on the index once the bounds bind as NUMERIC. Keep them
     * decimal all the way from the {@code bbox} parameter; if a double ever has to be converted, use
     * {@link BigDecimal#valueOf(double)} and never {@code new BigDecimal(double)} — the latter turns 1.9
     * into 1.8999999999999999111…, which silently moves the boundary.
     */
    record Query(UUID orgId, String subjectType, String subjectId,
            BigDecimal minLat, BigDecimal minLng, BigDecimal maxLat, BigDecimal maxLng) {

        boolean hasBoundingBox() {
            return minLat != null && minLng != null && maxLat != null && maxLng != null;
        }
    }

    /** One page of stamps plus the keyset cursor for the next (null when the last page). */
    record Page(List<GeoStamp> stamps, boolean hasMore, String nextCursor) {
    }
}
