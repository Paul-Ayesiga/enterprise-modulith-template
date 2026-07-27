package ug.co.smsone.settings.internal;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.CursorPageRequest;

@Service
@Transactional
public class FeatureFlagService {

    public static final String FLAGS_CACHE = "feature-flags";

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final FeatureFlagRepository repository;

    public FeatureFlagService(FeatureFlagRepository repository) {
        this.repository = repository;
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
                q -> q.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition()));
    }

    @CacheEvict(cacheNames = FLAGS_CACHE, key = "#key")
    public FeatureFlag set(String key, boolean enabled, String description) {
        FeatureFlag flag = repository.findByKey(key)
                .map(existing -> {
                    existing.toggle(enabled, description);
                    return existing;
                })
                .orElseGet(() -> FeatureFlag.create(key, enabled, description));
        return repository.save(flag);
    }
}
