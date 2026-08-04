package ug.co.smsone.geo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.geo.CaptureMode;
import ug.co.smsone.shared.geo.GeoFix;
import ug.co.smsone.shared.geo.GeoSource;
import ug.co.smsone.shared.geo.GeoStamp;
import ug.co.smsone.shared.geo.GeoStamps;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Geolocation through the real stack: the {@code shared.geo.GeoStamps} port, capture-policy
 * enforcement, the {@code GeoSearch} bbox seam, and coarsening — all against a real Postgres, which
 * also proves the V47 migration applies and Hibernate {@code validate} matches it. Each test uses a
 * fresh org id so tenant scoping keeps them isolated without truncation.
 */
class GeoStampTest extends AbstractIntegrationTest {

    // Kampala; six-decimal precision survives the numeric(9,6) round-trip exactly.
    private static final double LAT = 0.347596;
    private static final double LNG = 32.582520;

    @Autowired
    private GeoStamps geoStamps;

    @Autowired
    private GeoPolicyService policies;

    @Autowired
    private GeoQueryService query;

    private static CursorPageRequest firstPage() {
        return new CursorPageRequest(20, null);
    }

    private static GeoFix fix(double lat, double lng, Double accuracyM) {
        return new GeoFix(lat, lng, accuracyM, null, GeoSource.DEVICE_GPS, Instant.parse("2026-08-05T10:00:00Z"), null);
    }

    @Test
    void attachStoresTheStampAndFindReturnsItWithExactCoordinates() {
        UUID org = UUID.randomUUID();
        policies.set(org, "field_inspection", CaptureMode.OPTIONAL, null, null, null, null, null);

        GeoStamp stored = geoStamps.attach(org, "field_inspection", "insp-1", fix(LAT, LNG, 8.0));

        assertThat(stored.latitude()).isEqualTo(LAT);
        assertThat(stored.longitude()).isEqualTo(LNG);
        List<GeoStamp> found = geoStamps.findFor(org, "field_inspection", "insp-1");
        assertThat(found).singleElement().satisfies(s -> {
            assertThat(s.latitude()).isEqualTo(LAT);
            assertThat(s.longitude()).isEqualTo(LNG);
            assertThat(s.source()).isEqualTo(GeoSource.DEVICE_GPS);
        });
    }

    @Test
    void attachIsRejectedWhenCaptureIsOffOrUnconfigured() {
        UUID org = UUID.randomUUID();
        // No policy set → mode OFF → capture is opt-in per record type, so this must be refused.
        assertThatThrownBy(() -> geoStamps.attach(org, "field_inspection", "insp-1", fix(LAT, LNG, 8.0)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aFixTooCoarseForThePolicyIsRejected() {
        UUID org = UUID.randomUUID();
        policies.set(org, "asset_tag", CaptureMode.REQUIRED, new BigDecimal("10"), null, null, null, null);

        // accuracy 50 m is worse than the required 10 m.
        assertThatThrownBy(() -> geoStamps.attach(org, "asset_tag", "asset-9", fix(LAT, LNG, 50.0)))
                .isInstanceOf(ValidationException.class);

        // A precise-enough fix under the same policy is accepted.
        assertThat(geoStamps.attach(org, "asset_tag", "asset-9", fix(LAT, LNG, 4.0))).isNotNull();
    }

    @Test
    void modeForReflectsTheConfiguredPolicy() {
        UUID org = UUID.randomUUID();
        assertThat(geoStamps.modeFor(org, "unset_type")).isEqualTo(CaptureMode.OFF);
        policies.set(org, "incident", CaptureMode.REQUIRED, null, null, null, null, null);
        assertThat(geoStamps.modeFor(org, "incident")).isEqualTo(CaptureMode.REQUIRED);
    }

    @Test
    void boundingBoxSearchReturnsStampsInsideAndExcludesOutside() {
        UUID org = UUID.randomUUID();
        policies.set(org, "sighting", CaptureMode.OPTIONAL, null, null, null, null, null);
        geoStamps.attach(org, "sighting", "in", fix(LAT, LNG, 5.0));       // inside the box below
        geoStamps.attach(org, "sighting", "out", fix(1.5, 34.0, 5.0));     // far outside

        GeoSearch.Query box = new GeoSearch.Query(org, null, null, 0.30, 32.50, 0.40, 32.60);
        GeoSearch.Page page = query.search(box, firstPage(), true);

        assertThat(page.stamps()).singleElement()
                .satisfies(s -> assertThat(s.subjectId()).isEqualTo("in"));
    }

    @Test
    void nonPreciseReadCoarsensCoordinatesAndDropsAccuracy() {
        UUID org = UUID.randomUUID();
        policies.set(org, "delivery", CaptureMode.OPTIONAL, null, null, null, null, null);
        geoStamps.attach(org, "delivery", "d-1", fix(LAT, LNG, 6.0));

        GeoSearch.Query all = new GeoSearch.Query(org, "delivery", "d-1", null, null, null, null);

        GeoStamp precise = query.search(all, firstPage(), true).stamps().get(0);
        assertThat(precise.latitude()).isEqualTo(LAT);
        assertThat(precise.accuracyM()).isEqualTo(6.0);

        GeoStamp coarse = query.search(all, firstPage(), false).stamps().get(0);
        assertThat(coarse.latitude()).isEqualTo(0.35);   // ~1.1 km
        assertThat(coarse.longitude()).isEqualTo(32.58);
        assertThat(coarse.accuracyM()).isNull();
    }

    @Test
    void searchIsScopedToOneTenant() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        policies.set(orgA, "note", CaptureMode.OPTIONAL, null, null, null, null, null);
        geoStamps.attach(orgA, "note", "n-1", fix(LAT, LNG, 5.0));

        GeoSearch.Query fromB = new GeoSearch.Query(orgB, null, null, null, null, null, null);
        assertThat(query.search(fromB, firstPage(), true).stamps()).isEmpty();
    }
}
