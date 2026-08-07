package ug.co.smsone.support.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * A tenant's support tickets — open, read, reply. {@code ticket:read} sees the org's tickets and
 * their public messages; {@code ticket:write} opens and replies; INTERNAL platform notes are never
 * returned here. Escalation and assignment are the platform's job ({@code /api/v1/admin/tickets}).
 *
 * <p>The reads are open to any credential holding the permission; the two WRITES additionally need a
 * person, so an API key that holds {@code ticket:write} still gets a 403 from {@link SupportService}.
 * That is not an oversight in the permission model — the desk records who is asking so it can answer
 * them, and a key names no one to answer.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/tickets")
class OrgTicketController {

    private final SupportService support;

    OrgTicketController(SupportService support) {
        this.support = support;
    }

    record OpenRequest(String subject, String category, String priority) {
    }

    record ReplyRequest(String body) {
    }

    @PostMapping
    @Operation(summary = "Open a support ticket")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'ticket:write')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject open(@PathVariable UUID orgId, @RequestBody OpenRequest request, CurrentUser user) {
        return TicketResources.toResource(
                support.open(orgId, user.personId(), request.subject(), request.category(), request.priority()));
    }

    @GetMapping
    @Operation(summary = "List the organization's tickets")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'ticket:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(support.listForOrg(orgId, page), page, TicketResources::toResource);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one ticket")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'ticket:read')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable UUID id) {
        return TicketResources.toResource(support.requireInOrg(orgId, id));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "The ticket's public messages",
            description = "Oldest first, cursor-paged. Internal platform notes are never shown to "
                    + "the tenant — they are excluded by the query, not hidden by this layer.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'ticket:read')")
    WindowedResult<ResourceObject> messages(@PathVariable UUID orgId, @PathVariable UUID id,
            CursorPageRequest page) {
        support.requireInOrg(orgId, id); // 404 for a foreign org's ticket
        return WindowedResult.of(support.messages(id, false, page), page, TicketResources::toResource);
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Reply to your ticket")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'ticket:write')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject reply(@PathVariable UUID orgId, @PathVariable UUID id,
            @RequestBody ReplyRequest request, CurrentUser user) {
        return TicketResources.toResource(support.tenantReply(orgId, id, user.personId(), request.body()));
    }
}
