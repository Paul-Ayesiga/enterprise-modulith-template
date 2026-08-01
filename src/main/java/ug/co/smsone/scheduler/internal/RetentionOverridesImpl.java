package ug.co.smsone.scheduler.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.retention.RetentionOverrides;

/**
 * The scheduler owns per-org retention overrides — it is the platform's retention home. Serves the
 * shared {@link RetentionOverrides} port to the retention jobs (read) and the admin surface
 * (write, audited). {@code @Primary} so the real impl wins over the shared no-op fallback wherever
 * both are on the context.
 */
@Component
@Primary
class RetentionOverridesImpl implements RetentionOverrides {

    private final OrgRetentionOverrideRepository overrides;
    private final AuditLog auditLog;

    RetentionOverridesImpl(OrgRetentionOverrideRepository overrides, AuditLog auditLog) {
        this.overrides = overrides;
        this.auditLog = auditLog;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> daysByScope(String scope) {
        Map<UUID, Integer> result = new LinkedHashMap<>();
        for (OrgRetentionOverride override : overrides.findByScope(scope)) {
            result.put(override.getOrgId(), override.getRetentionDays());
        }
        return result;
    }

    @Transactional
    OrgRetentionOverride set(UUID orgId, String scope, int retentionDays) {
        String before = overrides.findByOrgIdAndScope(orgId, scope)
                .map(existing -> String.valueOf(existing.getRetentionDays())).orElse(null);
        OrgRetentionOverride override = overrides.findByOrgIdAndScope(orgId, scope)
                .map(existing -> {
                    existing.retain(retentionDays);
                    return existing;
                })
                .orElseGet(() -> OrgRetentionOverride.of(orgId, scope, retentionDays));
        overrides.save(override);
        auditLog.record("retention.override_set", orgId, orgId + ":" + scope,
                before == null ? null : before + "d", retentionDays + "d");
        return override;
    }

    @Transactional
    void clear(UUID orgId, String scope) {
        overrides.deleteByOrgIdAndScope(orgId, scope);
        auditLog.record("retention.override_cleared", orgId, orgId + ":" + scope, null, null);
    }

    @Transactional(readOnly = true)
    List<OrgRetentionOverride> list(UUID orgId) {
        return overrides.findByOrgIdOrderByScopeAsc(orgId);
    }
}
