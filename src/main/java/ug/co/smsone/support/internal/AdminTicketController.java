package ug.co.smsone.support.internal;

import io.swagger.v3.oas.annotations.Operation;
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
 *
 * <h2>Why every route here declares a tenant axis (ADR 0010 §2, §3.4)</h2>
 *
 * <p>This is {@code /api/v1/admin/…}, so {@code CurrentUserFilter}'s org-scoped pattern does not match
 * it — deliberately, because the caller is an operator who belongs to no tenant — and the request
 * therefore arrives on the PLATFORM axis. Every table the desk touches is the opposite:
 * {@code ticket} and {@code ticket_message} are TENANT-tier and addressed bare, so on the platform
 * axis they resolve to nothing and the whole support desk answers 500. The tenant is not knowable
 * before the read either — the ticket id is what names it, and nothing in the URL says whose it is —
 * which is why the span is resolved by {@link TicketFanOut} rather than taken from the path.
 *
 * <p>One span per route and not one per service call: {@link #messages} makes two calls that are both
 * the same tenant's rows, and a second span would be a second connection and so a second transaction.
 * The mapping to resources runs INSIDE the span for the same reason
 * {@code AdminOrganizationController.listMembers} does — a window whose rows outlive the schema they
 * came from is a lazy-load waiting to fail.
 *
 * <h2>Phase 5: the span is no longer one schema, and every route here had to change</h2>
 *
 * <p>Pinning the pooled axis was right while every ticket was in {@code tenant_pool} and became a silent
 * defect the moment one organization was promoted: the queue would answer without that tenant's tickets
 * and the single-ticket routes with a 404, with nothing in either case saying a home had been missed.
 * {@link TicketFanOut} is where the reach lives now — a merge over every home for {@link #queue}, and a
 * home lookup by ticket id for the rest — and it is a separate bean rather than a method on
 * {@code SupportService} because the axis must be pinned before {@code @Transactional} opens the
 * transaction, which a self-invocation could not do (AGENTS §4.3).
 */
@RestController
@RequestMapping("/api/v1/admin/tickets")
class AdminTicketController {

    private final SupportService support;
    private final TicketFanOut homes;

    AdminTicketController(SupportService support, TicketFanOut homes) {
        this.support = support;
        this.homes = homes;
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
        // One keyset statement against platform.ticket_index since V61, not a merge over every home —
        // and therefore nothing to pin here, because the projection is platform-tier and TicketIndex
        // borrows the platform axis itself. The response shape is byte-identical to the merge's: the
        // index carries exactly the attributes this resource renders, which is why that column set is
        // what it is (see V61's header).
        return homes.queue(status, page, TicketResources::toResource);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one ticket (any org)")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject get(@PathVariable UUID id) {
        return homes.onTicketsHome(id, () -> TicketResources.toResource(support.requireAnyOrg(id)));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "All the ticket's messages, including internal notes",
            description = "Oldest first, cursor-paged.")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> messages(@PathVariable UUID id, CursorPageRequest page) {
        return homes.onTicketsHome(id, () -> {
            support.requireAnyOrg(id); // an unknown ticket is a 404, not an empty conversation
            return WindowedResult.of(support.messages(id, true, page), page, TicketResources::toResource);
        });
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Reply (public) or add an internal note",
            description = "A public reply stamps the first-response SLA and notifies the opener; "
                    + "`internal:true` is a platform-only note.")
    @PreAuthorize("hasRole('platform-support')")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ResourceObject reply(@PathVariable UUID id, @RequestBody ReplyRequest request, CurrentUser user) {
        return homes.onTicketsHome(id, () -> TicketResources.toResource(
                support.platformReply(id, user.personId(), request.body(), request.internal())));
    }

    @PostMapping("/{id}/assignment")
    @Operation(summary = "Assign the ticket",
            description = "Body `assigneePersonId` is the operator's person id (the id "
                    + "`/api/v1/admin/users` lists), not a name or a login.")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject assign(@PathVariable UUID id, @RequestBody AssignRequest request) {
        return homes.onTicketsHome(id,
                () -> TicketResources.toResource(support.assign(id, request.assigneePersonId())));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Transition the ticket's status")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject status(@PathVariable UUID id, @RequestBody StatusRequest request) {
        return homes.onTicketsHome(id,
                () -> TicketResources.toResource(support.changeStatus(id, request.status())));
    }
}
