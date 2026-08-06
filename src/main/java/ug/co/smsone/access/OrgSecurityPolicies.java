package ug.co.smsone.access;

import java.util.UUID;

/**
 * Read-side port over an organization's security policy for other modules. The organization module
 * uses it at invite time: an org that requires MFA gets its new members enrolled (CONFIGURE_TOTP)
 * on their very first login instead of slipping in single-factor.
 */
public interface OrgSecurityPolicies {

    /** True when the org's policy requires multi-factor sessions. No policy row = false. */
    boolean requiresMfa(UUID orgId);

    /**
     * True when the client address satisfies the org's IP allowlist. No policy row, or a blank
     * allowlist, means open (the platform default). Consulted by surfaces the URL-shaped
     * {@code OrgPolicyEnforcementFilter} cannot see — the MCP dispatcher — with the SAME judged
     * address rule: pass the proxy-aware client IP, never the raw socket peer.
     */
    boolean ipAllowed(UUID orgId, String clientIp);
}
