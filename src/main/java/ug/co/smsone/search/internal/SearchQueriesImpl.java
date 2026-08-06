package ug.co.smsone.search.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.search.SearchQueries;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/** The {@link SearchQueries} port: the tenant-scoped query, never the platform-wide one. */
@Component
class SearchQueriesImpl implements SearchQueries {

    private final SearchQueryService search;

    SearchQueriesImpl(SearchQueryService search) {
        this.search = search;
    }

    @Override
    @Transactional(readOnly = true)
    public WindowedResult<HitView> search(UUID orgId, String query, String entityType,
            CursorPageRequest page) {
        return search.search(orgId, false, entityType, query, page,
                hit -> new HitView(hit.entityType(), hit.entityId(), hit.title(), hit.snippet(),
                        hit.rank()));
    }
}
