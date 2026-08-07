package ug.co.smsone.support.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The platform support queue: the cross-tenant view, assignment, replies (public or internal
 * notes), and status transitions. platform-support throughout; escalation is automatic
 * (SlaEscalationJob), so there is no manual escalate endpoint.
 *
 * <p>Assignment and authorship are {@code person.id}s — the operator is a person who is a member of
 * no tenant, which is precisely why one identity table had to cover both sides of the desk.
 */
@RestController
@RequestMapping("/api/v1/admin/tickets")
class AdminTicketController {

    private final SupportService support;

    AdminTicketController(SupportService support) {
        this.support = support;
    }

    record ReplyRequest(String body, boolean internal) {
    }

    /** The operator to put on the ticket, by {@code person.id} — a platform operator, not a member. */
    record AssignRequest(UUID assigneePersonId) {
    }

    record StatusRequest(String status) {
    }

    @GetMapping
    @Operation(summary = "The support queue",
            description = "Cross-tenant; `?status=` narrows (OPEN / IN_PROGRESS / …).")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> queue(@RequestParam(name = "status", required = false) String status,
            CursorPageRequest page) {
        return WindowedResult.of(support.queue(status, page), page, TicketResources::toResource);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one ticket (any org)")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject get(@PathVariable UUID id) {
        return TicketResources.toResource(support.requireAnyOrg(id));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "All the ticket's messages, including internal notes")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> messages(@PathVariable UUID id) {
        support.requireAnyOrg(id);
        return support.messages(id, true).stream().map(TicketResources::toResource).toList();
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Reply (public) or add an internal note",
            description = "A public reply stamps the first-response SLA and notifies the opener; "
                    + "`internal:true` is a platform-only note.")
    @PreAuthorize("hasRole('platform-support')")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ResourceObject reply(@PathVariable UUID id, @RequestBody ReplyRequest request, CurrentUser user) {
        return TicketResources.toResource(
                support.platformReply(id, user.personId(), request.body(), request.internal()));
    }

    @PostMapping("/{id}/assignment")
    @Operation(summary = "Assign the ticket",
            description = "Body `assigneePersonId` is the operator's person id (the id "
                    + "`/api/v1/admin/users` lists), not a name or a login.")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject assign(@PathVariable UUID id, @RequestBody AssignRequest request) {
        return TicketResources.toResource(support.assign(id, request.assigneePersonId()));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Transition the ticket's status")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject status(@PathVariable UUID id, @RequestBody StatusRequest request) {
        return TicketResources.toResource(support.changeStatus(id, request.status()));
    }
}
