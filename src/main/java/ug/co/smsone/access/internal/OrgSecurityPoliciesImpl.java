package ug.co.smsone.access.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.access.OrgSecurityPolicies;

/** The {@link OrgSecurityPolicies} port: a policy-less org has the open platform default. */
@Component
class OrgSecurityPoliciesImpl implements OrgSecurityPolicies {

    private final OrgSecurityPolicyRepository policies;

    OrgSecurityPoliciesImpl(OrgSecurityPolicyRepository policies) {
        this.policies = policies;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean requiresMfa(UUID orgId) {
        return policies.findByOrgId(orgId).map(OrgSecurityPolicy::isRequireMfa).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean ipAllowed(UUID orgId, String clientIp) {
        return policies.findByOrgId(orgId)
                .map(OrgSecurityPolicy::getIpAllowlist)
                .filter(allowlist -> !allowlist.isBlank())
                .map(allowlist -> CidrMatcher.matchesAny(allowlist, clientIp))
                .orElse(true);
    }
}
