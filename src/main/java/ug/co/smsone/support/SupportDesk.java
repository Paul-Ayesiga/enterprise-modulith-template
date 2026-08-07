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
 *
 * <p>The writes take a {@code person.id} and the reads hand one back. A caller with no person — a
 * machine key — cannot open or reply: {@code opener_person_id} and {@code author_person_id} are NOT
 * NULL (V36) because a ticket exists to be answered, and an agent acting for nobody leaves the desk
 * nobody to answer. Callers holding a key must therefore expect a 403 on the two write methods
 * rather than a durable ticket attributed to a credential.
 */
public interface SupportDesk {

    WindowedResult<TicketView> list(UUID orgId, CursorPageRequest page);

    TicketView get(UUID orgId, UUID ticketId);

    TicketView open(UUID orgId, UUID openerPersonId, String subject, String category, String priority);

    /** The tenant-visible conversation — internal (platform-only) notes are filtered out. */
    List<MessageView> messages(UUID orgId, UUID ticketId);

    MessageView reply(UUID orgId, UUID ticketId, UUID authorPersonId, String body);

    /** {@code subject} here is the ticket's TITLE — the one "subject" in this platform that stayed. */
    record TicketView(UUID id, String subject, String category, String priority, String status,
            UUID openerPersonId, Instant openedAt) {
    }

    record MessageView(UUID id, UUID authorPersonId, String body, Instant createdAt) {
    }
}
