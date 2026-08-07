package ug.co.smsone.geo.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.geo.GeoFix;
import ug.co.smsone.shared.geo.GeoSource;
import ug.co.smsone.shared.geo.GeoStamp;
import ug.co.smsone.shared.geo.GeoStamps;
import ug.co.smsone.shared.geo.PlaceLabel;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.PageMeta;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Attach a location to a record and query stamps. Capture needs {@code geo:capture}; reading needs
 * {@code geo:read} and returns coordinates coarsened to ~1.1&nbsp;km unless the caller also holds
 * {@code geo:read_precise}, which returns exact positions. Queries are keyset-paginated and filterable
 * by subject ({@code subjectType}+{@code subjectId}) or a {@code bbox=minLat,minLng,maxLat,maxLng}.
 */
@RestController
class GeoController {

    private static final String RESOURCE_TYPE = "geo-stamp";
    private static final BigDecimal LAT_LIMIT = new BigDecimal("90");
    private static final BigDecimal LNG_LIMIT = new BigDecimal("180");
    // Comfortably past the 53 fractional digits a double's exact decimal expansion can reach, and far
    // inside what Postgres numeric can represent. See corner() for why an unbounded scale is a hazard.
    private static final int MAX_CORNER_SCALE = 100;

    private final GeoStamps geoStamps;
    private final GeoQueryService query;
    private final PermissionEvaluator permissionEvaluator;

    GeoController(GeoStamps geoStamps, GeoQueryService query, PermissionEvaluator permissionEvaluator) {
        this.geoStamps = geoStamps;
        this.query = query;
        this.permissionEvaluator = permissionEvaluator;
    }

    record StampRequest(String subjectType, String subjectId, Double latitude, Double longitude,
            Double accuracyM, Double altitudeM, GeoSource source, Instant capturedAt, String consentRef) {
    }

    record StampAttributes(String subjectType, String subjectId, double latitude, double longitude,
            Double accuracyM, Double altitudeM, String source, String capturedByPersonId, Instant capturedAt,
            String consentRef, String countryCode, String admin1, String locality, String formattedAddress) {
    }

    @PostMapping("/api/v1/orgs/{orgId}/geo/stamps")
    @Operation(summary = "Attach a location to a record",
            description = "Validates the fix against this org's capture policy for the record-type, then stores it.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'geo:capture')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject attach(@PathVariable UUID orgId, @RequestBody StampRequest body) {
        if (body == null || body.latitude() == null || body.longitude() == null
                || body.source() == null || body.capturedAt() == null) {
            throw new ValidationException("latitude, longitude, source and capturedAt are required.",
                    ApiSource.pointer("/data/attributes"));
        }
        GeoFix fix = new GeoFix(body.latitude(), body.longitude(), body.accuracyM(), body.altitudeM(),
                body.source(), body.capturedAt(), body.consentRef());
        return toResource(geoStamps.attach(orgId, body.subjectType(), body.subjectId(), fix));
    }

    @GetMapping("/api/v1/orgs/{orgId}/geo/stamps")
    @Operation(summary = "Query geo stamps",
            description = """
                    Newest first, filterable by subject (`subjectType`+`subjectId`) or a \
                    `bbox=minLat,minLng,maxLat,maxLng`. Exact coordinates require `geo:read_precise`; \
                    otherwise positions are coarsened.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'geo:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String bbox,
            CursorPageRequest page) {
        GeoSearch.Query q = buildQuery(orgId, subjectType, subjectId, bbox);
        GeoSearch.Page result = query.search(q, page, hasPrecise(orgId));
        List<ResourceObject> items = result.stamps().stream().map(GeoController::toResource).toList();
        return new WindowedResult<>(items, new PageMeta(page.size(), items.size(), result.hasMore(), result.nextCursor()));
    }

    private boolean hasPrecise(UUID orgId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && permissionEvaluator.hasPermission(auth, orgId, "organization", "geo:read_precise");
    }

    /**
     * Parses {@code bbox} STRAIGHT to {@link BigDecimal} — never via {@code double}. The bounds are
     * compared against a {@code numeric(9,6)} column, and a FLOAT8 bind makes Postgres cast the COLUMN
     * rather than the literal, which puts {@code geo_stamp_bbox_idx} out of reach (see
     * {@link GeoSearch.Query}). Routing the text through {@code Double.parseDouble} and converting back
     * would be worse than useless: {@code new BigDecimal(1.9d)} is 1.8999999999999999111…, so the box
     * edge quietly moves and a stamp sitting exactly on the boundary drops out of the result.
     */
    private static GeoSearch.Query buildQuery(UUID orgId, String subjectType, String subjectId, String bbox) {
        BigDecimal minLat = null;
        BigDecimal minLng = null;
        BigDecimal maxLat = null;
        BigDecimal maxLng = null;
        if (bbox != null && !bbox.isBlank()) {
            String[] parts = bbox.split(",");
            if (parts.length != 4) {
                throw new ValidationException("bbox must be 'minLat,minLng,maxLat,maxLng'.", ApiSource.parameter("bbox"));
            }
            minLat = corner(parts[0], LAT_LIMIT);
            minLng = corner(parts[1], LNG_LIMIT);
            maxLat = corner(parts[2], LAT_LIMIT);
            maxLng = corner(parts[3], LNG_LIMIT);
        }
        return new GeoSearch.Query(orgId, subjectType, subjectId, minLat, minLng, maxLat, maxLng);
    }

    /**
     * One bbox corner as an exact decimal, bounded to what a coordinate can be. The bounds check is not
     * decoration: {@code BigDecimal} parses "1E+999999999" and "1E-999999999" happily, and a NUMERIC
     * bind of either makes <em>Postgres</em> fail the statement with "value overflows numeric format" —
     * a 500 for what is plainly bad input. Magnitude alone does not cover it (1E-999999999 is tiny and
     * still unrepresentable), hence the scale check; both comparisons are cheap, because
     * {@code compareTo} settles differing scales on adjusted exponents without expanding anything.
     * The accepted range is the one the capture path already enforces (GeoFix.hasValidCoordinates), so
     * a box can only be built from coordinates a stamp could have been taken at.
     */
    private static BigDecimal corner(String text, BigDecimal limit) {
        BigDecimal value;
        try {
            value = new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException("bbox coordinates must be numbers.", ApiSource.parameter("bbox"));
        }
        if (value.abs().compareTo(limit) > 0 || value.scale() > MAX_CORNER_SCALE) {
            throw new ValidationException(
                    "bbox latitudes must be within [-90, 90] and longitudes within [-180, 180].",
                    ApiSource.parameter("bbox"));
        }
        return value;
    }

    private static ResourceObject toResource(GeoStamp stamp) {
        PlaceLabel place = stamp.place();
        return new ResourceObject(stamp.id().toString(), RESOURCE_TYPE, new StampAttributes(
                stamp.subjectType(), stamp.subjectId(), stamp.latitude(), stamp.longitude(),
                stamp.accuracyM(), stamp.altitudeM(), stamp.source().name(),
                stamp.capturedByPersonId() == null ? null : stamp.capturedByPersonId().toString(),
                stamp.capturedAt(), stamp.consentRef(),
                place == null ? null : place.countryCode(), place == null ? null : place.admin1(),
                place == null ? null : place.locality(), place == null ? null : place.formattedAddress()));
    }
}
