package ug.co.smsone.geo.internal;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.geo.GeoStamp;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * The read side. Delegates the spatial query to {@link GeoSearch}, then — for callers without
 * {@code geo:read_precise} — coarsens each coordinate to ~1.1&nbsp;km and strips accuracy/altitude, so
 * a broad {@code geo:read} grant reveals whereabouts without exact positions (AGENTS least-privilege).
 */
@Service
class GeoQueryService {

    private static final int COARSE_DECIMALS = 2; // ~1.1 km

    private final GeoSearch search;

    GeoQueryService(GeoSearch search) {
        this.search = search;
    }

    @Transactional(readOnly = true)
    GeoSearch.Page search(GeoSearch.Query query, CursorPageRequest page, boolean precise) {
        GeoSearch.Page result = search.search(query, page);
        if (precise) {
            return result;
        }
        List<GeoStamp> coarsened = result.stamps().stream().map(GeoQueryService::coarsen).toList();
        return new GeoSearch.Page(coarsened, result.hasMore(), result.nextCursor());
    }

    private static GeoStamp coarsen(GeoStamp s) {
        return new GeoStamp(s.id(), s.orgId(), s.subjectType(), s.subjectId(),
                round(s.latitude()), round(s.longitude()), null, null,
                s.source(), s.capturedByPersonId(), s.capturedAt(), s.consentRef(), s.place());
    }

    private static double round(double value) {
        double factor = Math.pow(10, COARSE_DECIMALS);
        return Math.round(value * factor) / factor;
    }
}
