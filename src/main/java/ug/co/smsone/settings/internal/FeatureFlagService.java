package ug.co.smsone.settings.internal;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.CursorPageRequest;

@Service
@Transactional
public class FeatureFlagService {

    public static final String FLAGS_CACHE = "feature-flags";

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final FeatureFlagRepository repository;
    private final FeatureFlagOrgOverrideRepository overrides;
    private final AuditLog auditLog;
    private final java.time.Clock clock;

    public FeatureFlagService(FeatureFlagRepository repository,
            FeatureFlagOrgOverrideRepository overrides, AuditLog auditLog, java.time.Clock clock) {
        this.repository = repository;
        this.overrides = overrides;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** Hot path for guarding features — unknown flags are OFF, never an error. */
    @Cacheable(cacheNames = FLAGS_CACHE, key = "#key")
    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        return repository.findByKey(key).map(FeatureFlag::isEnabled).orElse(false);
    }

    @Transactional(readOnly = true)
    public FeatureFlag require(String key) {
        return repository.findByKey(key)
                .orElseThrow(() -> new NotFoundException("Feature flag '" + key + "' does not exist."));
    }

    @Transactional(readOnly = true)
    public Window<FeatureFlag> list(CursorPageRequest page) {
        return repository.findBy((root, query, cb) -> cb.conjunction(),
                q -> q.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
    }

    /**
     * Org-aware evaluation, deliberately UNCACHED (the global {@link #isEnabled} keeps the hot
     * cache): a hard org override beats everything; otherwise a disabled flag is off for everyone;
     * otherwise a percentage buckets deterministically per (flag, org) so an org's answer is sticky
     * across requests and instances; no percentage = plain enabled.
     */
    @Transactional(readOnly = true)
    public boolean isEnabledFor(String key, java.util.UUID orgId) {
        var override = overrides.findByFlagKeyAndOrgId(key, orgId);
        if (override.isPresent()) {
            return override.get().isEnabled();
        }
        return repository.findByKey(key).map(flag -> {
            if (!flag.isEnabled()) {
                return false;
            }
            Integer percentage = flag.getPercentage();
            if (percentage == null) {
                return true;
            }
            return Math.floorMod((key + "|" + orgId).hashCode(), 100) < percentage;
        }).orElse(false);
    }

    public FeatureFlagOrgOverride setOrgOverride(String key, java.util.UUID orgId, boolean enabled) {
        require(key); // an override on a nonexistent flag is a typo, not a wish
        FeatureFlagOrgOverride override = overrides.findByFlagKeyAndOrgId(key, orgId)
                .map(existing -> {
                    existing.set(enabled);
                    return existing;
                })
                .orElseGet(() -> FeatureFlagOrgOverride.of(key, orgId, enabled, clock.instant()));
        FeatureFlagOrgOverride saved = overrides.save(override);
        auditLog.record("settings.feature_flag_org_override", orgId, key, null, String.valueOf(enabled));
        return saved;
    }

    public void clearOrgOverride(String key, java.util.UUID orgId) {
        overrides.findByFlagKeyAndOrgId(key, orgId).ifPresent(override -> {
            overrides.delete(override);
            auditLog.record("settings.feature_flag_org_override_cleared", orgId, key,
                    String.valueOf(override.isEnabled()), null);
        });
    }

    @CacheEvict(cacheNames = FLAGS_CACHE, key = "#key")
    public FeatureFlag set(String key, boolean enabled, String description, Integer percentage) {
        if (percentage != null && (percentage < 0 || percentage > 100)) {
            throw new ug.co.smsone.shared.error.ValidationException(
                    "percentage must be between 0 and 100.",
                    ug.co.smsone.shared.web.ApiSource.pointer("/data/attributes/percentage"));
        }
        var existing = repository.findByKey(key);
        Boolean previous = existing.map(FeatureFlag::isEnabled).orElse(null);
        FeatureFlag flag = existing
                .map(current -> {
                    current.toggle(enabled, description);
                    return current;
                })
                .orElseGet(() -> FeatureFlag.create(key, enabled, description));
        flag.rollout(percentage);
        FeatureFlag saved = repository.save(flag);
        auditLog.record("settings.feature_flag_changed", null, key,
                previous == null ? null : String.valueOf(previous), String.valueOf(enabled)
                        + (percentage == null ? "" : " pct=" + percentage));
        return saved;
    }

    /**
     * The only sanctioned way to remove a flag: {@code repository.delete(..)} soft-deletes the row but
     * evicts nothing, so {@link #isEnabled} would keep answering {@code true} out of L1/L2 for the full
     * {@code app.cache.l2-ttl}. Deleting a flag is a way to kill a feature; a kill switch that takes ten
     * minutes to reach the fleet — and only after the flag has visibly disappeared — is not one.
     */
    @CacheEvict(cacheNames = FLAGS_CACHE, key = "#key")
    public void delete(String key) {
        FeatureFlag flag = require(key);
        repository.delete(flag);
        auditLog.record("settings.feature_flag_deleted", null, key, String.valueOf(flag.isEnabled()), null);
    }
}
