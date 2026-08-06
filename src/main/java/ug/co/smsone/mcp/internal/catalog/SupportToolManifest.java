package ug.co.smsone.mcp.internal.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.mcp.internal.ToolDefinition;
import ug.co.smsone.mcp.internal.ToolDefinition.Kind;
import ug.co.smsone.mcp.internal.ToolManifest;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.support.SupportDesk;

/**
 * Support tools — the tenant side of the desk: open a ticket, read the conversation, reply.
 * {@code ticket:read} gates the reads and {@code ticket:write} the writes, mirroring REST; the
 * opener/author recorded is the caller's durable subject ({@code key:<id>} for agents), so
 * attribution stays honest.
 */
@Component
class SupportToolManifest implements ToolManifest {

    private final SupportDesk desk;

    SupportToolManifest(SupportDesk desk) {
        this.desk = desk;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ToolDefinition("tickets_list", "List support tickets",
                        "List this organization's support tickets (subject, category, priority, "
                                + "status, opener).",
                        1, "support", Kind.READ, "ticket:read",
                        ToolArgs.schema(ToolArgs.pageProperties()),
                        (context, args) -> desk.list(context.orgId(), ToolArgs.page(args))),

                new ToolDefinition("ticket_get", "Get support ticket",
                        "Read one support ticket's summary.",
                        1, "support", Kind.READ, "ticket:read",
                        ToolArgs.schema(Map.of("ticket_id", ToolArgs.string("The ticket id.")),
                                "ticket_id"),
                        (context, args) -> desk.get(context.orgId(), id(args))),

                new ToolDefinition("ticket_create", "Open support ticket",
                        "Open a support ticket for this organization. The opener recorded is this "
                                + "credential's subject.",
                        1, "support", Kind.WRITE, "ticket:write",
                        ToolArgs.schema(openProperties(), "subject"),
                        (context, args) -> desk.open(context.orgId(), context.subject(),
                                ToolArgs.requireString(args, "subject"),
                                ToolArgs.optionalString(args, "category"),
                                ToolArgs.optionalString(args, "priority"))),

                new ToolDefinition("ticket_messages", "Ticket conversation",
                        "The tenant-visible conversation on a ticket, oldest first. Platform-internal "
                                + "notes are never included.",
                        1, "support", Kind.READ, "ticket:read",
                        ToolArgs.schema(Map.of("ticket_id", ToolArgs.string("The ticket id.")),
                                "ticket_id"),
                        (context, args) -> Map.of("messages", desk.messages(context.orgId(), id(args)))),

                new ToolDefinition("ticket_reply", "Reply on ticket",
                        "Add a tenant reply to a ticket's conversation.",
                        1, "support", Kind.WRITE, "ticket:write",
                        ToolArgs.schema(Map.of(
                                        "ticket_id", ToolArgs.string("The ticket id."),
                                        "body", ToolArgs.string("The reply text.")),
                                "ticket_id", "body"),
                        (context, args) -> desk.reply(context.orgId(), id(args), context.subject(),
                                ToolArgs.requireString(args, "body"))));
    }

    private static UUID id(Map<String, Object> args) {
        try {
            return UUID.fromString(ToolArgs.requireString(args, "ticket_id"));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("'ticket_id' must be a UUID.", ApiSource.pointer("/ticket_id"));
        }
    }

    private static Map<String, Object> openProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subject", ToolArgs.string("One-line summary of the issue."));
        properties.put("category", ToolArgs.string("Optional category (billing, technical, …)."));
        properties.put("priority", ToolArgs.string("Optional priority: P1 (most urgent) to P4."));
        return properties;
    }
}
