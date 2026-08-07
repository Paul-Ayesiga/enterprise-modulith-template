package ug.co.smsone.exchange.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
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
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Recurring exports for one tenant. Creating needs the handler's export permission (checked
 * programmatically, like a one-off submit); the fired jobs then appear in the ordinary jobs
 * listing, attributed to the schedule's requester.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/exchange/schedules")
class ExchangeScheduleController {

    static final String RESOURCE_TYPE = "exchange-schedule";

    private final ExchangeScheduleService schedules;

    ExchangeScheduleController(ExchangeScheduleService schedules) {
        this.schedules = schedules;
    }

    record ScheduleAttributes(String handler, String format, String cron, boolean enabled,
            Instant nextRunAt, String requesterPersonId, String lastJobId) {
    }

    record CreateRequest(String handler, String format, String cron) {
    }

    @PostMapping
    @Operation(summary = "Create a recurring export",
            description = """
                    Fires the handler's export on a six-field Spring cron (UTC), running as YOU — \
                    if your export permission is later revoked, the schedule disables itself \
                    rather than keep exporting. Exports only: an import has no source to re-read.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'exchange:submit')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@PathVariable UUID orgId, @RequestBody CreateRequest request,
            CurrentUser user) {
        return toResource(schedules.create(user, orgId, request.handler(),
                request.format() == null ? "CSV" : request.format(), request.cron()));
    }

    @GetMapping
    @Operation(summary = "List the organization's recurring exports")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'exchange:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(schedules.list(orgId, page), page,
                ExchangeScheduleController::toResource);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a recurring export",
            description = "Allowed to its creator, or to anyone holding the handler's export permission.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'exchange:submit')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID orgId, @PathVariable UUID id, CurrentUser user) {
        schedules.delete(user, orgId, id);
    }

    static ResourceObject toResource(ExchangeSchedule schedule) {
        return new ResourceObject(schedule.getId().toString(), RESOURCE_TYPE,
                new ScheduleAttributes(schedule.getHandler(), schedule.getFormat(),
                        schedule.getCron(), schedule.isEnabled(), schedule.getNextRunAt(),
                        schedule.getRequesterPersonId().toString(),
                        schedule.getLastJobId() == null ? null : schedule.getLastJobId().toString()));
    }
}
