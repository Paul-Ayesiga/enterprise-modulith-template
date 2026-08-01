package ug.co.smsone.access.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * An org's security policy (get-or-default, upsert) and the org's grant of device TRUST. Trust is
 * an org act, not a self-claim: {@code require_trusted_device} means "a device THIS ORG has
 * blessed", so blessing one is gated on {@code org:update} like the policy itself.
 */
@Service
class SecurityPolicyService {

    private final OrgSecurityPolicyRepository policies;
    private final UserDeviceRepository devices;
    private final AuditLog auditLog;

    SecurityPolicyService(OrgSecurityPolicyRepository policies, UserDeviceRepository devices, AuditLog auditLog) {
        this.policies = policies;
        this.devices = devices;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    OrgSecurityPolicy view(UUID orgId) {
        return policies.findByOrgId(orgId).orElseGet(() -> OrgSecurityPolicy.of(orgId));
    }

    @Transactional
    OrgSecurityPolicy upsert(UUID orgId, String ipAllowlist, boolean requireTrustedDevice, Long sessionMaxAge) {
        String cleaned = normalizeCidrs(ipAllowlist);
        if (sessionMaxAge != null && sessionMaxAge <= 0) {
            throw new ValidationException("sessionMaxAgeSeconds must be positive when set.",
                    ApiSource.pointer("/data/attributes/sessionMaxAgeSeconds"));
        }
        OrgSecurityPolicy policy = policies.findByOrgId(orgId).orElseGet(() -> OrgSecurityPolicy.of(orgId));
        policy.update(cleaned, requireTrustedDevice, sessionMaxAge);
        OrgSecurityPolicy saved = policies.save(policy);
        auditLog.record("access.security_policy_updated", orgId, orgId.toString(), null,
                "ipAllowlist=[" + (cleaned == null ? "" : cleaned) + "] trustedDevice=" + requireTrustedDevice
                        + " sessionMaxAge=" + sessionMaxAge);
        return saved;
    }

    @Transactional
    void setDeviceTrust(UUID orgId, String subject, UUID deviceId, boolean trusted) {
        UserDevice device = devices.findByIdAndSubject(deviceId, subject)
                .orElseThrow(() -> new NotFoundException("Device not found for that user."));
        device.setTrusted(trusted);
        devices.save(device);
        auditLog.record(trusted ? "access.device_trusted" : "access.device_untrusted",
                orgId, deviceId.toString(), null, "subject=" + subject);
    }

    /** A blank/whitespace value clears the allowlist; each CIDR is validated so a typo 422s at set. */
    private static String normalizeCidrs(String ipAllowlist) {
        if (ipAllowlist == null || ipAllowlist.isBlank()) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder();
        for (String raw : ipAllowlist.split(",")) {
            String cidr = raw.trim();
            if (cidr.isEmpty()) {
                continue;
            }
            if (!cidr.contains("/") || !CidrMatcher.matchesAny(cidr, hostOf(cidr))) {
                throw new ValidationException("Not a valid CIDR: " + cidr,
                        ApiSource.pointer("/data/attributes/ipAllowlist"));
            }
            cleaned.append(cleaned.isEmpty() ? "" : ",").append(cidr);
        }
        return cleaned.isEmpty() ? null : cleaned.toString();
    }

    /** A CIDR always contains its own network address, so this validates the rule parses. */
    private static String hostOf(String cidr) {
        return cidr.substring(0, cidr.indexOf('/'));
    }
}
