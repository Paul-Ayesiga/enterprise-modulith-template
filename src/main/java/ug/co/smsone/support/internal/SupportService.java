package ug.co.smsone.support.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Ticket lifecycle. A tenant opens and converses on its own org's tickets; platform-support works
 * the queue — assign, public reply (which stamps the first-response clock and notifies the opener),
 * internal note (never shown to the tenant), status transitions. SLA due dates are stamped at open
 * from the org's per-priority SLA override when one is set, else the seeded per-priority policy.
 *
 * <p>Openers, authors and assignees are {@code person.id}s. This service is the single place both
 * protocol surfaces (REST and the {@code SupportDesk} port) funnel through, so it is where the
 * "a ticket needs a person" rule is stated once — see {@link #requirePerson}.
 *
 * <p><b>It is also the single place the cross-tenant operator queue is maintained from</b>
 * ({@link TicketIndex}, V61). Every method here that changes a column the operator's queue renders calls
 * {@code index.record(ticket)} immediately after the save and inside the same transaction — which is
 * exactly why the maintenance belongs here and not in the two controllers: being the one funnel is what
 * makes "every ticket write updates the queue" a property of the code rather than a convention two
 * protocol surfaces have to remember. The upsert is idempotent and guarded by
 * {@code is distinct from}, so calling it on a write that changed nothing indexed costs no row.
 */
@Service
class SupportService {

    private static final List<String> PRIORITIES = List.of("P1", "P2", "P3", "P4");
    private static final List<String> STATUSES =
            List.of("OPEN", "IN_PROGRESS", "WAITING_ON_CUSTOMER", "RESOLVED", "CLOSED");

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final SlaPolicyRepository slaPolicies;
    private final OrgSlaOverrideRepository slaOverrides;
    private final SupportNotifier notifier;
    private final AuditLog auditLog;
    private final TicketIndex index;
    private final CrossDatabaseWrites platformTier;
    private final Clock clock;

    SupportService(TicketRepository tickets, TicketMessageRepository messages,
            SlaPolicyRepository slaPolicies, OrgSlaOverrideRepository slaOverrides,
            SupportNotifier notifier, AuditLog auditLog, TicketIndex index,
            CrossDatabaseWrites platformTier, Clock clock) {
        this.tickets = tickets;
        this.messages = messages;
        this.slaPolicies = slaPolicies;
        this.slaOverrides = slaOverrides;
        this.notifier = notifier;
        this.auditLog = auditLog;
        this.index = index;
        this.platformTier = platformTier;
        this.clock = clock;
    }

    @Transactional
    Ticket open(UUID orgId, UUID openerPersonId, String subject, String category, String priority) {
        UUID opener = requirePerson(openerPersonId, "the person who opened it");
        String normalizedPriority = priority == null ? "P3" : priority.trim().toUpperCase();
        if (!PRIORITIES.contains(normalizedPriority)) {
            throw new ValidationException("priority must be P1, P2, P3 or P4.",
                    ApiSource.pointer("/data/attributes/priority"));
        }
        if (subject == null || subject.isBlank() || subject.length() > 200) {
            throw new ValidationException("subject is required (max 200 characters).",
                    ApiSource.pointer("/data/attributes/subject"));
        }
        int[] sla = resolveSlaMinutes(orgId, normalizedPriority);
        Instant now = clock.instant();
        Ticket ticket = tickets.save(Ticket.open(orgId, opener, subject.trim(), category, normalizedPriority,
                now.plusSeconds(sla[0] * 60L),
                now.plusSeconds(sla[1] * 60L)));
        // AFTER the save, never before it: the id and the created stamp are assigned at persist, and
        // they are half the queue's keyset. Inside the transaction, so a rolled-back open leaves no
        // queue row on any deployment where the tenant is co-located with primary (TicketIndex).
        index.record(ticket);
        auditLog.record("support.ticket_opened", orgId, ticket.getId().toString(), null,
                "priority=" + normalizedPriority);
        notifier.ticketOpened(ticket); // support queue awareness
        return ticket;
    }

    /**
     * The SLA minutes for an org+priority: the org's override if set, else the seeded default.
     *
     * <p><b>The two reads are on opposite sides of the tenancy boundary and only one of them can use
     * the caller's connection.</b> {@code org_sla_override} is TENANT-tier, so it must be read on the
     * axis the caller already has — that is the whole point of an override. {@code sla_policy} is
     * PLATFORM-tier ({@code @Table(schema = "platform")}), and a tenant served from its own database
     * has no {@code platform.sla_policy} there: ADR 0011 §5.1's minimal remote schema holds only
     * {@code event_publication}. Left unhopped this threw
     * {@code relation "platform.sla_policy" does not exist} on the first ticket a remote tenant opened —
     * loudly, which is the tripwire working, but a tenant that cannot open a ticket is not served.
     *
     * <p>{@link CrossDatabaseWrites#callOnPlatform} compares DATABASES, never axes, so on every
     * deployment with no remote datasource this is the same connection and the same transaction it
     * always was.
     */
    private int[] resolveSlaMinutes(UUID orgId, String priority) {
        return slaOverrides.findByOrgIdAndPriority(orgId, priority)
                .map(override -> new int[]{override.getFirstResponseMinutes(), override.getResolutionMinutes()})
                .orElseGet(() -> platformTier.callOnPlatform(() -> {
                    SlaPolicy sla = slaPolicies.findByPriority(priority)
                            .orElseThrow(() -> new NotFoundException("No SLA policy seeded for " + priority));
                    return new int[]{sla.getFirstResponseMinutes(), sla.getResolutionMinutes()};
                }));
    }

    @Transactional
    OrgSlaOverride setSlaOverride(UUID orgId, String priority, int firstResponseMinutes, int resolutionMinutes) {
        String normalized = priority == null ? "" : priority.trim().toUpperCase();
        if (!PRIORITIES.contains(normalized)) {
            throw new ValidationException("priority must be P1, P2, P3 or P4.",
                    ApiSource.pointer("/data/attributes/priority"));
        }
        if (firstResponseMinutes <= 0 || resolutionMinutes <= 0) {
            throw new ValidationException("firstResponseMinutes and resolutionMinutes must be positive.");
        }
        OrgSlaOverride override = slaOverrides.findByOrgIdAndPriority(orgId, normalized)
                .map(existing -> {
                    existing.retarget(firstResponseMinutes, resolutionMinutes);
                    return existing;
                })
                .orElseGet(() -> OrgSlaOverride.of(orgId, normalized, firstResponseMinutes, resolutionMinutes));
        slaOverrides.save(override);
        auditLog.record("support.sla_override_set", orgId, orgId + ":" + normalized, null,
                "first=" + firstResponseMinutes + "m resolution=" + resolutionMinutes + "m");
        return override;
    }

    @Transactional
    void clearSlaOverride(UUID orgId, String priority) {
        String normalized = priority == null ? "" : priority.trim().toUpperCase();
        slaOverrides.deleteByOrgIdAndPriority(orgId, normalized);
        auditLog.record("support.sla_override_cleared", orgId, orgId + ":" + normalized, null, null);
    }

    @Transactional(readOnly = true)
    List<OrgSlaOverride> listSlaOverrides(UUID orgId) {
        return slaOverrides.findByOrgIdOrderByPriorityAsc(orgId);
    }

    @Transactional(readOnly = true)
    Window<Ticket> listForOrg(UUID orgId, CursorPageRequest page) {
        return tickets.pageByOrg(orgId, page);
    }

    // There is no `queue(status, page)` here any more, and its absence is the V61 change seen from this
    // side: the cross-tenant operator queue is not a per-home read that something merges, it is one
    // keyset statement against platform.ticket_index (TicketIndex, TicketFanOut). A per-home queue read
    // kept "just in case" would be a second definition of the operator's collection, and the two would
    // disagree about ordering or filtering the first time either moved.

    @Transactional(readOnly = true)
    Ticket requireInOrg(UUID orgId, UUID id) {
        return tickets.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    @Transactional(readOnly = true)
    Ticket requireAnyOrg(UUID id) {
        return tickets.findById(id).orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    /**
     * One page of a ticket's conversation, oldest first. {@code includeInternal} is the PLATFORM
     * view; a tenant read passes false and the note never leaves the database — the cut is a
     * predicate in {@link TicketMessageRepository#pageByTicket}, not a filter applied to rows this
     * caller was not entitled to read in the first place.
     */
    @Transactional(readOnly = true)
    Window<TicketMessage> messages(UUID ticketId, boolean includeInternal, CursorPageRequest page) {
        return messages.pageByTicket(ticketId, includeInternal, page);
    }

    /** The tenant's own reply. Moves a WAITING ticket back to IN_PROGRESS. */
    @Transactional
    TicketMessage tenantReply(UUID orgId, UUID id, UUID authorPersonId, String body) {
        UUID author = requirePerson(authorPersonId, "the person who wrote each message");
        Ticket ticket = requireInOrg(orgId, id);
        requireBody(body);
        if ("WAITING_ON_CUSTOMER".equals(ticket.getStatus())) {
            ticket.changeStatus("IN_PROGRESS");
            tickets.save(ticket);
            // A tenant reply is the one write that moves a ticket's status without an operator doing
            // anything, so it is also the one whose queue row goes stale unnoticed — the desk would keep
            // showing WAITING_ON_CUSTOMER for a customer who has already answered.
            index.record(ticket);
        }
        return messages.save(TicketMessage.of(ticket.getId(), author, body.trim(), false, clock.instant()));
    }

    /** A platform reply — public (stamps first response, notifies the opener) or an internal note. */
    @Transactional
    TicketMessage platformReply(UUID id, UUID authorPersonId, String body, boolean internal) {
        UUID author = requirePerson(authorPersonId, "the person who wrote each message");
        Ticket ticket = requireAnyOrg(id);
        requireBody(body);
        if (!internal) {
            ticket.firstResponded(clock.instant());
            tickets.save(ticket);
            // Only the public branch: an internal note changes no column the queue renders, so indexing
            // it would be a write the `is distinct from` guard would discard anyway.
            index.record(ticket);
            notifier.ticketReplied(ticket);
        }
        return messages.save(TicketMessage.of(ticket.getId(), author, body.trim(), internal, clock.instant()));
    }

    @Transactional
    Ticket assign(UUID id, UUID assigneePersonId) {
        if (assigneePersonId == null) {
            throw new ValidationException("assigneePersonId is required (the operator's person id).",
                    ApiSource.pointer("/data/attributes/assigneePersonId"));
        }
        Ticket ticket = requireAnyOrg(id);
        ticket.assign(assigneePersonId);
        Ticket saved = tickets.save(ticket);
        index.record(saved);
        auditLog.record("support.ticket_assigned", ticket.getOrgId(), id.toString(), null,
                "assignee=" + assigneePersonId);
        return saved;
    }

    @Transactional
    Ticket changeStatus(UUID id, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!STATUSES.contains(normalized)) {
            throw new ValidationException("status must be one of " + STATUSES + ".",
                    ApiSource.pointer("/data/attributes/status"));
        }
        Ticket ticket = requireAnyOrg(id);
        String before = ticket.getStatus();
        ticket.changeStatus(normalized);
        Ticket saved = tickets.save(ticket);
        index.record(saved);
        auditLog.record("support.ticket_status_changed", ticket.getOrgId(), id.toString(),
                "status=" + before, "status=" + normalized);
        return saved;
    }

    /**
     * A machine key resolves to no person — that is normal everywhere else, where the absence simply
     * means {@code created_by} is NULL (V10). Support is the one surface where it cannot be waved
     * through: {@code opener_person_id} and {@code author_person_id} are NOT NULL because a ticket
     * with no human behind it has no one to reply TO, and the desk's whole job is the conversation.
     * So an API key is refused here, in words that name the credential, rather than being allowed to
     * reach the database and surface a not-null violation as a 500.
     */
    private static UUID requirePerson(UUID personId, String what) {
        if (personId == null) {
            throw new ForbiddenException("A support ticket records " + what
                    + "; an API key has no person to record. Open it as a signed-in member.");
        }
        return personId;
    }

    private static void requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ValidationException("body must not be blank.", ApiSource.pointer("/data/attributes/body"));
        }
    }
}
