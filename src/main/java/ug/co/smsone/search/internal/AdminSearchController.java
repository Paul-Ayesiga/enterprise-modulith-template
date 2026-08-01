package ug.co.smsone.search.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Platform-wide search: everything tenant search sees plus the null-org rows (e.g. users). Its own
 * controller so the class-level {@code /api/v1/admin} mapping keeps it out of the
 * {@code X-Impersonate} documentation — an impersonated principal holds no platform role, so this
 * surface always refuses the header.
 */
@RestController
@RequestMapping("/api/v1/admin/search")
class AdminSearchController {

    private final SearchQueryService search;

    AdminSearchController(SearchQueryService search) {
        this.search = search;
    }

    @GetMapping
    @Operation(summary = "Search across the platform",
            description = "Everything tenant search sees plus platform-wide entries (e.g. users); "
                    + "`org` narrows to one organization.")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> search(@RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID org,
            CursorPageRequest page) {
        return search.search(org, true, type, q, page, SearchController::toResource);
    }
}
