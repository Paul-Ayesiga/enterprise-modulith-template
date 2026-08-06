package ug.co.smsone.mcp.internal.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ug.co.smsone.mcp.internal.ToolDefinition;
import ug.co.smsone.mcp.internal.ToolDefinition.Kind;
import ug.co.smsone.mcp.internal.ToolManifest;
import ug.co.smsone.organization.OrgDirectory;
import ug.co.smsone.organization.OrgMembers;
import ug.co.smsone.organization.OrgRoles;

/**
 * Organization tools — profile, members, roles. Permissions mirror the REST controllers exactly;
 * every handler is one port call, and everything the services enforce (escalation guard, members
 * cap, last-owner lock) fires here identically.
 */
@Component
class OrganizationToolManifest implements ToolManifest {

    private final OrgDirectory directory;
    private final OrgMembers members;
    private final OrgRoles roles;

    OrganizationToolManifest(OrgDirectory directory, OrgMembers members, OrgRoles roles) {
        this.directory = directory;
        this.members = members;
        this.roles = roles;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ToolDefinition("org_get", "Get organization",
                        "Read this organization's profile: alias, display name, status, created date.",
                        1, "organization", Kind.READ, "org:read",
                        ToolDefinition.noArguments(),
                        (context, args) -> directory.get(context.orgId())),

                new ToolDefinition("org_update", "Rename organization",
                        "Rename this organization (its display name; the alias is permanent).",
                        1, "organization", Kind.WRITE, "org:update",
                        ToolArgs.schema(Map.of("name", ToolArgs.string("The new display name.")), "name"),
                        (context, args) -> directory.rename(context.orgId(),
                                ToolArgs.requireString(args, "name"))),

                new ToolDefinition("members_list", "List members",
                        "List this organization's members (subject, role code, status, member since). "
                                + "Cursor-paginated.",
                        1, "organization", Kind.READ, "member:read",
                        ToolArgs.schema(ToolArgs.pageProperties()),
                        (context, args) -> members.list(context.orgId(), ToolArgs.page(args))),

                new ToolDefinition("member_invite", "Invite member",
                        "Invite a person into this organization with a role. Provisions the identity if "
                                + "new, emails temporary credentials, and records the membership; "
                                + "re-inviting an existing member is idempotent and does NOT re-role them. "
                                + "You must already hold every permission the role carries.",
                        1, "organization", Kind.WRITE, "member:invite",
                        ToolArgs.schema(inviteProperties(), "email", "role_code"),
                        (context, args) -> members.invite(context.orgId(),
                                ToolArgs.requireString(args, "email"),
                                ToolArgs.optionalString(args, "first_name"),
                                ToolArgs.optionalString(args, "last_name"),
                                ToolArgs.requireString(args, "role_code"))),

                new ToolDefinition("member_role_assign", "Change a member's role",
                        "Assign an existing member a different role (by role code). You must already "
                                + "hold every permission the new role carries; demoting the last OWNER "
                                + "is refused.",
                        1, "organization", Kind.WRITE, "member:role:assign",
                        ToolArgs.schema(Map.of(
                                        "subject", ToolArgs.string("The member's durable subject id."),
                                        "role_code", ToolArgs.string("The role code to assign.")),
                                "subject", "role_code"),
                        (context, args) -> members.assignRole(context.orgId(),
                                ToolArgs.requireString(args, "subject"),
                                ToolArgs.requireString(args, "role_code"))),

                new ToolDefinition("member_remove", "Remove member",
                        "Remove a member from this organization. Their platform account survives; the "
                                + "membership ends. Removing the last OWNER is refused.",
                        1, "organization", Kind.DESTRUCTIVE, "member:remove",
                        ToolArgs.schema(Map.of(
                                        "subject", ToolArgs.string("The member's durable subject id.")),
                                "subject"),
                        (context, args) -> {
                            String subject = ToolArgs.requireString(args, "subject");
                            members.remove(context.orgId(), subject);
                            return Map.of("removed", subject);
                        }),

                new ToolDefinition("roles_list", "List roles",
                        "List this organization's roles with their permission bundles. Role editing is "
                                + "not available over MCP by design — use the web console.",
                        1, "organization", Kind.READ, "role:read",
                        ToolArgs.schema(ToolArgs.pageProperties()),
                        (context, args) -> roles.list(context.orgId(), ToolArgs.page(args))));
    }

    private static Map<String, Object> inviteProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("email", ToolArgs.string("The invitee's email (becomes their username)."));
        properties.put("first_name", ToolArgs.string("Optional first name."));
        properties.put("last_name", ToolArgs.string("Optional last name."));
        properties.put("role_code", ToolArgs.string("The role to grant, by code (see roles_list)."));
        return properties;
    }
}
