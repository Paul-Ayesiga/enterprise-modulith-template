package ug.co.smsone.organization;

import java.util.Set;
import java.util.UUID;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Read-only role catalog port for other protocol surfaces (the MCP module today). Deliberately
 * list-only: role CRUD is a governance act that stays REST/human (MCP plan §8).
 */
public interface OrgRoles {

    WindowedResult<RoleView> list(UUID orgId, CursorPageRequest page);

    record RoleView(UUID roleId, String code, String name, String description,
            Set<String> permissions, boolean systemRole) {
    }
}
