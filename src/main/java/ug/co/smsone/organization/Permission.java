package ug.co.smsone.organization;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fixed catalog of org-scoped permission codes — the authorization vocabulary. Roles are DB-editable
 * bundles of these; an unknown code on a role write is rejected (never a silent bad grant).
 */
public enum Permission {

    ORG_READ("org:read"),
    ORG_UPDATE("org:update"),
    ORG_DELETE("org:delete"),
    ORG_SETTINGS_READ("org:settings:read"),
    ORG_SETTINGS_UPDATE("org:settings:update"),

    MEMBER_READ("member:read"),
    MEMBER_INVITE("member:invite"),
    MEMBER_REMOVE("member:remove"),
    MEMBER_ROLE_ASSIGN("member:role:assign"),

    ROLE_READ("role:read"),
    ROLE_CREATE("role:create"),
    ROLE_UPDATE("role:update"),
    ROLE_DELETE("role:delete"),

    AUDIT_READ("audit:read");

    private static final Map<String, Permission> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Permission::code, permission -> permission));

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Permission fromCode(String code) {
        Permission permission = BY_CODE.get(code);
        if (permission == null) {
            throw new IllegalArgumentException("Unknown permission code: " + code);
        }
        return permission;
    }

    public static boolean isValid(String code) {
        return BY_CODE.containsKey(code);
    }
}
