package ug.co.smsone.shared.security;

/**
 * The platform authorization axis: three Keycloak realm roles, mapped to Spring authorities as
 * {@code ROLE_<name>} by {@link KeycloakJwtAuthenticationConverter}. They are hierarchical — the
 * {@code RoleHierarchy} in {@link SecurityConfig} makes a higher tier satisfy every lower one — so a
 * check names the <em>minimum</em> tier that may pass it, never a set of tiers.
 *
 * <p>This axis is disjoint from the organization axis by design: a platform role grants no org
 * permission ({@link ApiPermissionEvaluator} has no role bypass). Reaching tenant data as a platform
 * operator is impersonation's job, which is audited; a role that silently widened is not.
 */
public final class PlatformRole {

    /** Read-only platform view — listings, audit, ops reads. The floor of the hierarchy. */
    public static final String SUPPORT = "platform-support";

    /** Changes platform behaviour — settings, feature flags, tenant lifecycle. Implies support. */
    public static final String ADMIN = "platform-admin";

    /** Escalation tier — everything admin can do, plus what only it may authorize. Implies admin. */
    public static final String SUPERADMIN = "platform-superadmin";

    private static final java.util.Set<String> ALL = java.util.Set.of(SUPPORT, ADMIN, SUPERADMIN);

    /**
     * Whether a realm role name carries platform authority. Use it to keep platform roles out of paths
     * that must never mint them — user provisioning, tenant role codes — rather than re-listing the
     * three names at each site and having one drift.
     */
    public static boolean isPlatformRole(String roleName) {
        return roleName != null && ALL.contains(roleName.trim());
    }

    private PlatformRole() {
    }
}
