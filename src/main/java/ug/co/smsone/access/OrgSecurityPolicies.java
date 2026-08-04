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
}
