package ug.co.smsone.support.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;
import ug.co.smsone.support.SupportDesk;

/**
 * The {@link SupportDesk} port: tenant-side mappings over {@link SupportService}. Messages always
 * pass {@code includeInternal=false} — the tenant view, never the platform one.
 */
@Component
class SupportDeskImpl implements SupportDesk {

    private final SupportService support;

    SupportDeskImpl(SupportService support) {
        this.support = support;
    }

    @Override
    @Transactional(readOnly = true)
    public WindowedResult<TicketView> list(UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(support.listForOrg(orgId, page), page, SupportDeskImpl::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketView get(UUID orgId, UUID ticketId) {
        return toView(support.requireInOrg(orgId, ticketId));
    }

    @Override
    public TicketView open(UUID orgId, UUID openerPersonId, String subject, String category,
            String priority) {
        return toView(support.open(orgId, openerPersonId, subject, category, priority));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageView> messages(UUID orgId, UUID ticketId) {
        Ticket ticket = support.requireInOrg(orgId, ticketId); // tenancy before any message read
        return support.messages(ticket.getId(), false).stream()
                .map(SupportDeskImpl::toView)
                .toList();
    }

    @Override
    public MessageView reply(UUID orgId, UUID ticketId, UUID authorPersonId, String body) {
        return toView(support.tenantReply(orgId, ticketId, authorPersonId, body));
    }

    private static TicketView toView(Ticket ticket) {
        return new TicketView(ticket.getId(), ticket.getSubject(), ticket.getCategory(),
                ticket.getPriority(), ticket.getStatus(), ticket.getOpenerPersonId(),
                ticket.getCreatedAt());
    }

    private static MessageView toView(TicketMessage message) {
        return new MessageView(message.getId(), message.getAuthorPersonId(), message.getBody(),
                message.getCreatedAt());
    }
}
