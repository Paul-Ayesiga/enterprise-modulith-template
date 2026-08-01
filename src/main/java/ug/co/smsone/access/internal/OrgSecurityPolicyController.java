package ug.co.smsone.access.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * An organization's own security policy — IP allowlist, require-a-trusted-device, session max age —
 * plus blessing a member's device as trusted. All on {@code org:update}: tightening how the org is
 * reached is org administration. A policy field left absent means the platform default (open).
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/security-policy")
class OrgSecurityPolicyController {

    static final String RESOURCE_TYPE = "security-policy";

    private final SecurityPolicyService policies;

    OrgSecurityPolicyController(SecurityPolicyService policies) {
        this.policies = policies;
    }

    record PolicyAttributes(String ipAllowlist, boolean requireTrustedDevice, Long sessionMaxAgeSeconds) {
    }

    record PolicyRequest(String ipAllowlist, boolean requireTrustedDevice, Long sessionMaxAgeSeconds) {
    }

    record TrustRequest(String subject, String deviceId, boolean trusted) {
    }

    @GetMapping
    @Operation(summary = "Read the organization's security policy",
            description = "Get-or-default: an org that never set one reads the open default, not a 404.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    ResourceObject get(@PathVariable UUID orgId) {
        return toResource(policies.view(orgId));
    }

    @PutMapping
    @Operation(summary = "Set the organization's security policy",
            description = """
                    Every field TIGHTENS access. `ipAllowlist` is comma-joined CIDRs (blank clears \
                    it); `requireTrustedDevice` needs an org-blessed `X-Device-Id`; \
                    `sessionMaxAgeSeconds` refuses tokens older than it for this org. A denial is a \
                    403 naming the rule.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    ResourceObject set(@PathVariable UUID orgId, @RequestBody PolicyRequest request) {
        return toResource(policies.upsert(orgId, request.ipAllowlist(), request.requireTrustedDevice(),
                request.sessionMaxAgeSeconds()));
    }

    @PostMapping("/trusted-devices")
    @Operation(summary = "Bless (or unbless) a member's device as trusted",
            description = "Trust is the org's grant, not a self-claim — that is why it lives here.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    ResourceObject trust(@PathVariable UUID orgId, @RequestBody TrustRequest request) {
        policies.setDeviceTrust(orgId, request.subject(), UUID.fromString(request.deviceId()), request.trusted());
        return toResource(policies.view(orgId));
    }

    private static ResourceObject toResource(OrgSecurityPolicy policy) {
        return new ResourceObject(policy.getOrgId().toString(), RESOURCE_TYPE,
                new PolicyAttributes(policy.getIpAllowlist(), policy.isRequireTrustedDevice(),
                        policy.getSessionMaxAgeSeconds()));
    }
}
