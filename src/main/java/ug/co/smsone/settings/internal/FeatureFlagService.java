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
    private final AuditLog auditLog;

    public FeatureFlagService(FeatureFlagRepository repository, AuditLog auditLog) {
        this.repository = repository;
        this.auditLog = auditLog;
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

    @CacheEvict(cacheNames = FLAGS_CACHE, key = "#key")
    public FeatureFlag set(String key, boolean enabled, String description) {
        var existing = repository.findByKey(key);
        Boolean previous = existing.map(FeatureFlag::isEnabled).orElse(null);
        FeatureFlag flag = existing
                .map(current -> {
                    current.toggle(enabled, description);
                    return current;
                })
                .orElseGet(() -> FeatureFlag.create(key, enabled, description));
        FeatureFlag saved = repository.save(flag);
        auditLog.record("settings.feature_flag_changed", null, key,
                previous == null ? null : String.valueOf(previous), String.valueOf(enabled));
        return saved;
    }
}
