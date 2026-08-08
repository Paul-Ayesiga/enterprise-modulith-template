package ug.co.smsone.scheduler.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.tenancy.TenantContext;
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
 * contract belongs to the org that signed it and travels with that org on extraction. The two callers
 * both pin: {@link AdminRetentionController} with the {@code orgId} in its path, and the retention jobs
 * with the pooled-tenant axis around the whole purge — which is why {@code daysByScope} can go on
 * answering for every org in one query while there is one schema, and becomes a query per home when
 * there is not (Phase 5).
 */
@Component
@Primary
class RetentionOverridesImpl implements RetentionOverrides {

    /**
     * The pooled tenant's axis, spelled the way three other files in this codebase spell it: an org id
     * that is in no {@code organization} row can only ever resolve to the shared {@code tenant_pool}.
     * Phase 5 replaces this constant with a loop, not with a different constant.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

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
     * Every organization's override for one scope — the CROSS-TENANT read the retention jobs consult
     * before they purge, which is why it returns a map keyed by org rather than answering for one.
     *
     * <p>{@code org_retention_override} is tenant-tier (ADR 0010 §2), so this needs a tenant axis even
     * though its callers are platform-side sweeps with no org in hand. Today one axis answers for
     * everyone, because nobody is promoted; after Phase 5 this is a loop over
     * {@code platform.tenant_placement} accumulating into the same map, and the map is already the
     * right shape for that — which is why the signature does not change then.
     *
     * <p>The pin is OUTSIDE the transaction and the transaction moved inside it: the schema is chosen
     * when the connection is borrowed, so a pin inside {@code @Transactional} is too late and
     * {@code TenantContext} refuses it outright.
     */
    @Override
    public Map<UUID, Integer> daysByScope(String scope) {
        return TenantContext.callAs(POOLED_TENANT, () -> transactions.execute(tx -> {
            Map<UUID, Integer> result = new LinkedHashMap<>();
            for (OrgRetentionOverride override : overrides.findByScope(scope)) {
                result.put(override.getOrgId(), override.getRetentionDays());
            }
            return result;
        }));
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
