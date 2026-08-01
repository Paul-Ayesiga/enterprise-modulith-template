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

/**
 * Platform settings CRUD with the cached hot read {@link #valueOf}. Public — the §2.1 exception,
 * with its why: the shared-kernel ITs outside this package (both Valkey cache tests, the event
 * registry purge test, audit capture, analytics seeding) drive the platform's reference
 * {@code @Cacheable} + event-publishing service through it, and a parallel test fixture would
 * duplicate exactly this shape.
 */
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

    /** The hot read path: cached in L1+L2, evicted on {@link #put} and {@link #delete}. */
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

    /**
     * The only sanctioned way to remove a setting, and the reason it exists is the {@code @CacheEvict}.
     * {@code repository.delete(..)} is a supported transition now that {@link Setting} is soft-deletable,
     * but it evicts nothing — {@link #valueOf} would keep serving the pre-delete value out of L1/L2 for
     * the full {@code app.cache.l2-ttl} while the database shows the row as gone, which is behaviour no
     * one can explain from the data.
     */
    @CacheEvict(cacheNames = VALUES_CACHE, key = "#key")
    public void delete(String key) {
        Setting setting = require(key);
        repository.delete(setting);
        auditLog.record("settings.deleted", null, key, setting.getValue(), null);
    }
}
