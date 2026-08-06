package ug.co.smsone.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Tenant-side support port for other protocol surfaces (the MCP module today) — the same tenant
 * operations the ticket REST controller performs: open, read, converse. Platform-side triage (the
 * queue, assignment, internal notes) is deliberately absent; internal messages never cross this
 * port.
 */
public interface SupportDesk {

    WindowedResult<TicketView> list(UUID orgId, CursorPageRequest page);

    TicketView get(UUID orgId, UUID ticketId);

    TicketView open(UUID orgId, String openerSubject, String subject, String category, String priority);

    /** The tenant-visible conversation — internal (platform-only) notes are filtered out. */
    List<MessageView> messages(UUID orgId, UUID ticketId);

    MessageView reply(UUID orgId, UUID ticketId, String authorSubject, String body);

    record TicketView(UUID id, String subject, String category, String priority, String status,
            String openerSubject, Instant openedAt) {
    }

    record MessageView(UUID id, String authorSubject, String body, Instant createdAt) {
    }
}
