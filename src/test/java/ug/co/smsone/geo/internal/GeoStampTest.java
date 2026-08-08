package ug.co.smsone.geo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.geo.CaptureMode;
import ug.co.smsone.shared.geo.GeoFix;
import ug.co.smsone.shared.geo.GeoSource;
import ug.co.smsone.shared.geo.GeoStamp;
import ug.co.smsone.shared.geo.GeoStamps;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Geolocation through the real stack: the {@code shared.geo.GeoStamps} port, capture-policy
 * enforcement, the {@code GeoSearch} bbox seam, and coarsening — all against a real Postgres, which
 * also proves the V47 migration applies and Hibernate {@code validate} matches it. Each test uses a
 * fresh org id so tenant scoping keeps them isolated without truncation. That isolation is also why
 * no test here asserts which plan node the bbox query gets: a handful of rows plans the same either
 * way, so the bind types are pinned by the cast in the plan text and by a stamp on the box edge.
 *
 * <p><strong>Every test declares an org axis.</strong> Both tables this class touches —
 * {@code geo_capture_policy} and {@code geo_stamp} — are tenant-tier (ADR 0010 §2), so they are
 * unqualified and only resolve on a {@code search_path} that leads with a tenant schema. The harness
 * pins PLATFORM, which is the honest axis for "the harness is not any tenant" and cannot see either
 * table; a request reaches them because the edge filter pinned the org from the path, and a test that
 * calls the service directly owes the same declaration. Hence
 * {@code TenantContext.runAs(org, …)} around the work rather than a wider pin.
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

    @Autowired
    private JdbcTemplate jdbc;

    private static CursorPageRequest firstPage() {
        return new CursorPageRequest(20, null);
    }

    private static GeoFix fix(double lat, double lng, Double accuracyM) {
        return new GeoFix(lat, lng, accuracyM, null, GeoSource.DEVICE_GPS, Instant.parse("2026-08-05T10:00:00Z"), null);
    }

    /**
     * A box from DECIMAL STRINGS. Never {@code new BigDecimal(someDouble)} here or in production: it
     * expands 0.35 to 0.34999999999999997779…, which moves the edge off the coordinate the caller asked
     * for — see {@link #aStampSittingExactlyOnTheBoxEdgeIsInsideIt()}.
     */
    private static GeoSearch.Query box(UUID org, String minLat, String minLng, String maxLat, String maxLng) {
        return new GeoSearch.Query(org, null, null, new BigDecimal(minLat), new BigDecimal(minLng),
                new BigDecimal(maxLat), new BigDecimal(maxLng));
    }

    @Test
    void attachStoresTheStampAndFindReturnsItWithExactCoordinates() {
        UUID org = UUID.randomUUID();
        TenantContext.runAs(org, () -> {
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
        });
    }

    @Test
    void attachIsRejectedWhenCaptureIsOffOrUnconfigured() {
        UUID org = UUID.randomUUID();
        // No policy set → mode OFF → capture is opt-in per record type, so this must be refused.
        // On the org's own axis, so the refusal is the policy's and not the router's: the assertion is
        // ValidationException, and an unresolvable geo_capture_policy would satisfy "threw" while
        // proving nothing about capture being opt-in.
        TenantContext.runAs(org, () ->
                assertThatThrownBy(() -> geoStamps.attach(org, "field_inspection", "insp-1", fix(LAT, LNG, 8.0)))
                        .isInstanceOf(ValidationException.class));
    }

    @Test
    void aFixTooCoarseForThePolicyIsRejected() {
        UUID org = UUID.randomUUID();
        TenantContext.runAs(org, () -> {
            policies.set(org, "asset_tag", CaptureMode.REQUIRED, new BigDecimal("10"), null, null, null, null);

            // accuracy 50 m is worse than the required 10 m.
            assertThatThrownBy(() -> geoStamps.attach(org, "asset_tag", "asset-9", fix(LAT, LNG, 50.0)))
                    .isInstanceOf(ValidationException.class);

            // A precise-enough fix under the same policy is accepted.
            assertThat(geoStamps.attach(org, "asset_tag", "asset-9", fix(LAT, LNG, 4.0))).isNotNull();
        });
    }

    @Test
    void modeForReflectsTheConfiguredPolicy() {
        UUID org = UUID.randomUUID();
        TenantContext.runAs(org, () -> {
            assertThat(geoStamps.modeFor(org, "unset_type")).isEqualTo(CaptureMode.OFF);
            policies.set(org, "incident", CaptureMode.REQUIRED, null, null, null, null, null);
            assertThat(geoStamps.modeFor(org, "incident")).isEqualTo(CaptureMode.REQUIRED);
        });
    }

    @Test
    void boundingBoxSearchReturnsStampsInsideAndExcludesOutside() {
        UUID org = UUID.randomUUID();
        TenantContext.runAs(org, () -> {
            policies.set(org, "sighting", CaptureMode.OPTIONAL, null, null, null, null, null);
            geoStamps.attach(org, "sighting", "in", fix(LAT, LNG, 5.0));       // inside the box below
            geoStamps.attach(org, "sighting", "out", fix(1.5, 34.0, 5.0));     // far outside

            GeoSearch.Page page = query.search(box(org, "0.30", "32.50", "0.40", "32.60"), firstPage(), true);

            assertThat(page.stamps()).singleElement()
                    .satisfies(s -> assertThat(s.subjectId()).isEqualTo("in"));
        });
    }

    @Test
    void aStampSittingExactlyOnTheBoxEdgeIsInsideIt() {
        UUID org = UUID.randomUUID();
        TenantContext.runAs(org, () -> {
            policies.set(org, "corner", CaptureMode.OPTIONAL, null, null, null, null, null);
            // Every corner below is a decimal whose nearest double lands on the WRONG side of it:
            // 0.1 -> 0.10000000000000000555… and 32.1 -> 32.10000000000000142… (above, so a min bound
            // built from a double climbs past the stamp), 0.35 -> 0.34999999999999997779… and
            // 32.55 -> 32.54999999999999715… (below, so a max bound sinks under it). A stamp parked on a
            // corner therefore vanishes the moment a bound is routed through a double — which is what
            // new BigDecimal(double) does. The bbox text must stay decimal from parse to bind.
            geoStamps.attach(org, "corner", "sw", fix(0.1, 32.1, 5.0));
            geoStamps.attach(org, "corner", "ne", fix(0.35, 32.55, 5.0));

            GeoSearch.Page page = query.search(box(org, "0.1", "32.1", "0.35", "32.55"), firstPage(), true);

            assertThat(page.stamps()).extracting(GeoStamp::subjectId).containsExactlyInAnyOrder("sw", "ne");
        });
    }

    @Test
    void theBoxBoundsBindAsNumericSoTheSpatialIndexStaysReachable() {
        // latitude/longitude are numeric(9,6). Bind a bound as FLOAT8 — which is what a `Double` field
        // on GeoSearch.Query gets you — and Postgres reconciles numeric-vs-float8 by casting the COLUMN:
        // the plan reads "(latitude)::double precision >= …", an expression that geo_stamp_bbox_idx
        // (org_id, latitude, longitude) cannot answer, so a bbox query seq-scans the whole tenant.
        // Asserting on the cast rather than on the chosen node is deliberate: the cast is a property of
        // the bind types and shows up in the plan at ANY table size, whereas which node the planner
        // picks depends on how much data happens to be there, and on a small table both bind types
        // plan the same seq scan — a test written against the row count would pass either way.
        GeoSearch.Query q = box(UUID.randomUUID(), "0.30", "32.50", "0.40", "32.60");
        // The org's own axis: `geo_stamp` is unqualified here on purpose — this is the statement the
        // repository issues, so it has to be planned on the search_path the repository would get.
        // Qualifying it, or planning it on the harness's platform pin, would test a different query
        // than the one production runs.
        String plan = TenantContext.callAs(q.orgId(), () -> String.join("\n", jdbc.query(
                "explain select id from geo_stamp where org_id = ? and deleted_at is null "
                        + "and latitude between ? and ? and longitude between ? and ?",
                (rs, rowNum) -> rs.getString(1),
                q.orgId(), q.minLat(), q.maxLat(), q.minLng(), q.maxLng())));

        assertThat(plan).doesNotContain("double precision");
    }

    @Test
    void nonPreciseReadCoarsensCoordinatesAndDropsAccuracy() {
        UUID org = UUID.randomUUID();
        TenantContext.runAs(org, () -> {
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
        });
    }

    @Test
    void searchIsScopedToOneTenant() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        // Two pins, one per org, because that is what the isolation claim actually is. Both orgs live
        // in `tenant_pool` today, so the emptiness below is enforced by the org_id predicate and not by
        // the schema — seeding and reading on one wide pin would hide exactly that.
        TenantContext.runAs(orgA, () -> {
            policies.set(orgA, "note", CaptureMode.OPTIONAL, null, null, null, null, null);
            geoStamps.attach(orgA, "note", "n-1", fix(LAT, LNG, 5.0));
        });

        GeoSearch.Query fromB = new GeoSearch.Query(orgB, null, null, null, null, null, null);
        assertThat(TenantContext.callAs(orgB, () -> query.search(fromB, firstPage(), true).stamps())).isEmpty();
    }
}
