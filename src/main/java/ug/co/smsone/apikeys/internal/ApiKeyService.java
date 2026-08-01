package ug.co.smsone.apikeys.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Minting and revoking machine keys. Org keys are capped at mint: the requested permission set
 * must be a SUBSET of what the caller holds in the org (the escalation guard's rule — a key can
 * never out-rank its creator). Platform keys are support-tier only and minted by platform-admin.
 * The plaintext secret exists exactly once, in the mint result.
 */
@Service
class ApiKeyService {

    private final ApiKeyRepository keys;
    private final OrgAuthorization orgAuthorization;
    private final CurrentUserProvider currentUser;
    private final AuditLog auditLog;
    private final Clock clock;

    ApiKeyService(ApiKeyRepository keys, OrgAuthorization orgAuthorization,
            CurrentUserProvider currentUser, AuditLog auditLog, Clock clock) {
        this.keys = keys;
        this.orgAuthorization = orgAuthorization;
        this.currentUser = currentUser;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    record Minted(ApiKey key, String secret) {
    }

    @Transactional
    Minted createOrgKey(UUID orgId, String name, Set<String> requestedPermissions, Instant expiresAt) {
        requireName(name);
        Set<String> requested = normalize(requestedPermissions);
        for (String code : requested) {
            if (!Permission.isValid(code)) {
                throw new ValidationException("Unknown permission code: " + code,
                        ApiSource.pointer("/data/attributes/permissions"));
            }
        }
        CurrentUser caller = currentUser.requireCurrentUser();
        Set<String> held = orgAuthorization.permissions(caller.subject(), orgId);
        List<String> escalated = requested.stream().filter(code -> !held.contains(code)).sorted().toList();
        if (!escalated.isEmpty()) {
            throw new ForbiddenException("A key cannot carry permissions you do not hold: "
                    + String.join(", ", escalated));
        }
        ApiKeyHashing.Minted minted = ApiKeyHashing.mint();
        ApiKey saved = keys.save(ApiKey.orgKey(orgId, name.trim(), minted.prefix(),
                minted.secretHash(), String.join(",", requested), expiresAt));
        auditLog.record("apikeys.key_created", orgId, saved.getId().toString(), null,
                "prefix=" + minted.prefix() + " permissions=[" + String.join(",", requested) + "]");
        return new Minted(saved, minted.presented());
    }

    @Transactional
    Minted createPlatformKey(String name, Instant expiresAt) {
        requireName(name);
        ApiKeyHashing.Minted minted = ApiKeyHashing.mint();
        // Support tier only, deliberately: a platform key reads; changing platform state stays a
        // human, higher-tier act. Widen this the day a machine genuinely needs to.
        ApiKey saved = keys.save(ApiKey.platformKey(name.trim(), minted.prefix(),
                minted.secretHash(), "platform-support", expiresAt));
        auditLog.record("apikeys.platform_key_created", null, saved.getId().toString(), null,
                "prefix=" + minted.prefix());
        return new Minted(saved, minted.presented());
    }

    @Transactional(readOnly = true)
    Window<ApiKey> listOrg(UUID orgId, CursorPageRequest page) {
        return keys.pageByOrg(orgId, page);
    }

    @Transactional(readOnly = true)
    Window<ApiKey> listPlatform(CursorPageRequest page) {
        return keys.pageByOrg(null, page);
    }

    @Transactional
    void revokeOrgKey(UUID orgId, UUID id) {
        ApiKey key = keys.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("API key not found."));
        keys.delete(key);
        auditLog.record("apikeys.key_revoked", orgId, id.toString(), "prefix=" + key.getPrefix(), null);
    }

    @Transactional
    void revokePlatformKey(UUID id) {
        ApiKey key = keys.findByIdAndOrgIdIsNull(id)
                .orElseThrow(() -> new NotFoundException("API key not found."));
        keys.delete(key);
        auditLog.record("apikeys.platform_key_revoked", null, id.toString(), "prefix=" + key.getPrefix(), null);
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ValidationException("name is required (max 100 characters).",
                    ApiSource.pointer("/data/attributes/name"));
        }
    }

    private static Set<String> normalize(Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ValidationException("permissions must not be empty.",
                    ApiSource.pointer("/data/attributes/permissions"));
        }
        return requested.stream().map(String::trim).filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
