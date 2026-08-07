package ug.co.smsone.access.internal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.security.OrgMembership;
import ug.co.smsone.shared.web.ApiSource;

/**
 * An org's security policy (get-or-default, upsert) and the org's grant of device TRUST. Trust is
 * an org act, not a self-claim: {@code require_trusted_device} means "a device THIS ORG has
 * blessed", so blessing one is gated on {@code org:update} like the policy itself — and since V51 the
 * grant belongs to the granting org rather than to the device, so "this org" means it literally.
 */
@Service
class SecurityPolicyService {

    private final OrgSecurityPolicyRepository policies;
    private final UserDeviceRepository devices;
    private final UserDeviceTrustRepository deviceTrust;
    private final OrgMembership orgMembership;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLog auditLog;
    private final Clock clock;

    SecurityPolicyService(OrgSecurityPolicyRepository policies, UserDeviceRepository devices,
            UserDeviceTrustRepository deviceTrust, OrgMembership orgMembership,
            CurrentUserProvider currentUserProvider, AuditLog auditLog, Clock clock) {
        this.policies = policies;
        this.devices = devices;
        this.deviceTrust = deviceTrust;
        this.orgMembership = orgMembership;
        this.currentUserProvider = currentUserProvider;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    OrgSecurityPolicy view(UUID orgId) {
        return policies.findByOrgId(orgId).orElseGet(() -> OrgSecurityPolicy.of(orgId));
    }

    @Transactional
    OrgSecurityPolicy upsert(UUID orgId, String ipAllowlist, boolean requireTrustedDevice, Long sessionMaxAge,
            boolean requireMfa) {
        String cleaned = normalizeCidrs(ipAllowlist);
        if (sessionMaxAge != null && sessionMaxAge <= 0) {
            throw new ValidationException("sessionMaxAgeSeconds must be positive when set.",
                    ApiSource.pointer("/data/attributes/sessionMaxAgeSeconds"));
        }
        OrgSecurityPolicy policy = policies.findByOrgId(orgId).orElseGet(() -> OrgSecurityPolicy.of(orgId));
        policy.update(cleaned, requireTrustedDevice, sessionMaxAge, requireMfa);
        OrgSecurityPolicy saved = policies.save(policy);
        auditLog.record("access.security_policy_updated", orgId, orgId.toString(), null,
                "ipAllowlist=[" + (cleaned == null ? "" : cleaned) + "] trustedDevice=" + requireTrustedDevice
                        + " sessionMaxAge=" + sessionMaxAge + " requireMfa=" + requireMfa);
        return saved;
    }

    /**
     * Grant or revoke THIS organization's trust in one of its members' devices.
     *
     * <p>Two things were wrong before, and only together did they add up to a bypass. The permission
     * check gates on the {@code orgId} in the path, but {@code personId} arrives in the request BODY and
     * was never checked against it — {@code ApiPermissionEvaluator} only ever compares {@code #orgId}
     * and never sees the person you named. And trust itself was a single global boolean on the device,
     * so a grant made anywhere satisfied {@code require_trusted_device} everywhere. The cheapest attack
     * needed no guessed identifiers at all: self-signup is open and a new org's creator is OWNER with
     * every permission, so make your own org, bless your own device with your own ids, and walk through
     * a different tenant's device policy.
     *
     * <p>The membership check closes the first half — you may only speak about your own members — and
     * V51's {@code user_device_trust} closes the second, by making the grant belong to the granting org.
     * Both are needed: without the membership check an org could grant trust over a stranger's device
     * (harmless to other orgs now, but still a write it has no business making, and it would leak the
     * existence of the device through the 404-vs-success difference).
     */
    @Transactional
    void setDeviceTrust(UUID orgId, UUID personId, UUID deviceId, boolean trusted) {
        if (!orgMembership.isMember(orgId, personId)) {
            // Deliberately the same 404 an unknown device gets: an org that may not act on this person
            // must not be able to tell a non-member from a non-existent one.
            throw new NotFoundException("Device not found for that user.");
        }
        UserDevice device = devices.findByIdAndPersonId(deviceId, personId)
                .orElseThrow(() -> new NotFoundException("Device not found for that user."));
        if (trusted) {
            // Re-granting is idempotent: the primary key is (device_id, org_id), so a second grant
            // would otherwise be a constraint violation rather than the no-op a caller expects.
            if (!deviceTrust.existsByIdDeviceIdAndIdOrgId(device.getId(), orgId)) {
                deviceTrust.save(UserDeviceTrust.of(device.getId(), orgId,
                        currentUserProvider.currentPersonId().orElse(null),
                        clock.instant()));
            }
        } else {
            deviceTrust.revoke(device.getId(), orgId);
        }
        auditLog.record(trusted ? "access.device_trusted" : "access.device_untrusted",
                orgId, deviceId.toString(), null, "personId=" + personId);
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
