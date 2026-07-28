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
public class SettingService {

    public static final String VALUES_CACHE = "setting-values";

    private final SettingRepository repository;
    private final AuditLog auditLog;

    public SettingService(SettingRepository repository, AuditLog auditLog) {
        this.repository = repository;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public Setting require(String key) {
        return repository.findByKey(key)
                .orElseThrow(() -> new NotFoundException("Setting '" + key + "' does not exist."));
    }

    /** Keyset-scrolled listing: newest first, id as tiebreaker — the sort the cursor encodes. */
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Transactional(readOnly = true)
    public Window<Setting> list(CursorPageRequest page) {
        return repository.findBy((root, query, cb) -> cb.conjunction(),
                q -> q.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
    }

    /** Hot read path for other modules: cached in L1+L2, evicted on {@link #put}. */
    @Cacheable(cacheNames = VALUES_CACHE, key = "#key")
    @Transactional(readOnly = true)
    public String valueOf(String key) {
        return repository.findByKey(key).map(Setting::getValue).orElse(null);
    }

    @CacheEvict(cacheNames = VALUES_CACHE, key = "#key")
    public Setting put(String key, String value, String description) {
        var existing = repository.findByKey(key);
        String previousValue = existing.map(Setting::getValue).orElse(null);
        Setting setting = existing
                .map(current -> {
                    current.change(value, description);
                    return current;
                })
                .orElseGet(() -> Setting.create(key, value, description));
        Setting saved = repository.save(setting);
        auditLog.record("settings.changed", null, key, previousValue, value);
        return saved;
    }
}
