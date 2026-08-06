package ug.co.smsone.organization.internal;

import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.OrgRoles;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/** The {@link OrgRoles} port: the role catalog, read-only by design (role CRUD stays REST/human). */
@Component
class OrgRolesImpl implements OrgRoles {

    private final RoleService roles;

    OrgRolesImpl(RoleService roles) {
        this.roles = roles;
    }

    @Override
    @Transactional(readOnly = true)
    public WindowedResult<RoleView> list(UUID orgId, CursorPageRequest page) {
        Window<Role> window = roles.list(orgId, page);
        return WindowedResult.of(window, page, role -> new RoleView(role.getId(), role.getCode(),
                role.getName(), role.getDescription(),
                role.getPermissions().stream().map(Permission::code).collect(Collectors.toUnmodifiableSet()),
                role.isSystemRole()));
    }
}
