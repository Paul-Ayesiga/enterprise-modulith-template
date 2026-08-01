package ug.co.smsone.maintenance.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Platform maintenance windows: platform-wide (no org) or targeted at one org. RESTRICT windows
 * pause org-scoped writes for their duration; ANNOUNCE windows are banner-only. platform-admin.
 */
@RestController
@RequestMapping("/api/v1/admin/maintenance")
class AdminMaintenanceController {

    private final MaintenanceService maintenance;

    AdminMaintenanceController(MaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    record ScheduleRequest(String orgId, Instant startsAt, Instant endsAt, String mode, String message) {
    }

    @PostMapping
    @Operation(summary = "Schedule a maintenance window",
            description = "`orgId` null = platform-wide. RESTRICT pauses org writes (503 + Retry-After) "
                    + "for the window; ANNOUNCE is banner-only.")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject schedule(@RequestBody ScheduleRequest request) {
        UUID orgId = request.orgId() == null || request.orgId().isBlank() ? null : UUID.fromString(request.orgId());
        return MaintenanceResources.toResource(maintenance.schedule(orgId, request.startsAt(),
                request.endsAt(), request.mode(), request.message()));
    }

    @GetMapping
    @Operation(summary = "List platform-wide maintenance windows")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> list() {
        return maintenance.list(null).stream().map(MaintenanceResources::toResource).toList();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a platform maintenance window")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID id) {
        maintenance.cancel(null, id);
    }
}
