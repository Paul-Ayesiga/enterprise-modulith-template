package ug.co.smsone.maintenance.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * A tenant's view of the maintenance windows in effect for it — platform-wide plus its own —
 * so a client can render a banner and know when writes are paused. Read-only for the tenant;
 * scheduling is the platform's act.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/maintenance")
class OrgMaintenanceController {

    private final MaintenanceService maintenance;

    OrgMaintenanceController(MaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @GetMapping
    @Operation(summary = "List maintenance windows affecting the organization",
            description = "`?active=true` narrows to windows in effect right now (for a banner).")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    List<ResourceObject> list(@PathVariable UUID orgId,
            @RequestParam(name = "active", defaultValue = "false") boolean active) {
        List<MaintenanceWindow> windows = active ? maintenance.activeFor(orgId) : maintenance.list(orgId);
        return windows.stream().map(MaintenanceResources::toResource).toList();
    }
}
