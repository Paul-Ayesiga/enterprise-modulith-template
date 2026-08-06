package ug.co.smsone.search.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.search.internal.SearchQueryService.SearchHit;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Tenant search, scoped to the caller's org by the same strict-id {@code hasPermission} gate as
 * every other org endpoint — the tenant cut happens inside the SQL, never after. Platform-wide
 * (null-org) rows are invisible here; {@link AdminSearchController} reaches those. Two controllers,
 * deliberately: the class-level mapping is what the OpenAPI customizer keys the
 * {@code X-Impersonate} exclusion on.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/search")
class SearchController {

    static final String RESOURCE_TYPE = "search-hit";

    private final SearchQueryService search;

    SearchController(SearchQueryService search) {
        this.search = search;
    }

    record SearchHitAttributes(String entityType, String entityId, String orgId, String title,
            String snippet, double score) {
    }

    @GetMapping
    @Operation(summary = "Search within one organization",
            description = """
                    Full-text first (`websearch_to_tsquery` syntax: quoted phrases, `-exclusions`, \
                    `or`); when nothing token-matches, a trigram pass over titles catches prefixes \
                    and typos. Ranked, cursor-paginated; `type` narrows to one entity type.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'search:query')")
    WindowedResult<ResourceObject> search(@PathVariable UUID orgId,
            @RequestParam String q,
            @RequestParam(required = false) String type,
            CursorPageRequest page) {
        return search.search(orgId, false, type, q, page, SearchController::toResource);
    }

    static ResourceObject toResource(SearchHit hit) {
        return new ResourceObject(hit.entityType() + ":" + hit.entityId(), RESOURCE_TYPE,
                new SearchHitAttributes(hit.entityType(), hit.entityId(),
                        hit.orgId() == null ? null : hit.orgId().toString(),
                        hit.title(), hit.snippet(), hit.rank()));
    }
}
