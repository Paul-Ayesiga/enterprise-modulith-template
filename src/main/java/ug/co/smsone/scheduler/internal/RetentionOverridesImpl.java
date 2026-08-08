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
 *
 * <p><b>{@code org_retention_override} is TENANT-tier (ADR 0010 §2), so every method here has to be
 * CALLED on a tenant axis</b> — none of them can declare one, because they are {@code @Transactional}
 * and the connection is bound before the body runs. That is not a detail of the table: a retention
 * contract belongs to the org that signed it and travels with that org on extraction. Both callers pin:
 * {@link AdminRetentionController} with the {@code orgId} in its path, and the retention jobs with the
 * home they are currently sweeping.
 *
 * <p><b>Since Phase 5 {@code daysByScope} answers for ONE HOME and no longer declares its own axis.</b>
 * It used to pin the pool, which was correct exactly while the pool was every tenant; a promoted
 * tenant's override lives in its own schema, so a pooled read would have returned no override for it and
 * the job would have purged that tenant's rows at the platform default — quietly overriding a contract
 * with the customer, in the direction that deletes data early. The pin moved out to the fan-out, where
 * it belongs, and the map this returns is per home because the purge that consumes it is too.
 */
@Component
@Primary
class RetentionOverridesImpl implements RetentionOverrides {

    private final OrgRetentionOverrideRepository overrides;
    private final AuditLog auditLog;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    RetentionOverridesImpl(OrgRetentionOverrideRepository overrides, AuditLog auditLog,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.overrides = overrides;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    /**
     * Every override for one scope <b>in the caller's current home</b>, keyed by org — one query for
     * however many tenants share that schema, which is thousands for {@code tenant_pool} and one for a
     * silo. That is why it still returns a map rather than answering for a single org: the shape is the
     * pool's, and a silo is simply a map with one entry.
     *
     * <p><b>It declares no axis, deliberately.</b> {@code org_retention_override} is tenant-tier
     * (ADR 0010 §2) and the caller is a fan-out that has already pinned the home it is sweeping; pinning
     * anything here would override that and read the wrong schema — which, before Phase 5's loop, is
     * exactly what a hard-coded pooled pin did to every promoted tenant. Called with no axis at all it
     * fails loudly on {@code relation "org_retention_override" does not exist}, which is the right
     * direction: a retention job that silently found no overrides would purge every org at the platform
     * default and there would be nothing to see.
     *
     * <p>A {@link org.springframework.transaction.support.TransactionTemplate} rather than
     * {@code @Transactional}, because the schema is chosen when the connection is borrowed: an
     * annotation here would bind the connection before the caller's pin could take effect on it.
     */
    @Override
    public Map<UUID, Integer> daysByScope(String scope) {
        return transactions.execute(tx -> {
            Map<UUID, Integer> result = new LinkedHashMap<>();
            for (OrgRetentionOverride override : overrides.findByScope(scope)) {
                result.put(override.getOrgId(), override.getRetentionDays());
            }
            return result;
        });
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
