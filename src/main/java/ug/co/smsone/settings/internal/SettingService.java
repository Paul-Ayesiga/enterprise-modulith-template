package ug.co.smsone.settings.internal;

import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.CursorPageRequest;

@Service
@Transactional
public class SettingService {

    private final SettingRepository repository;

    public SettingService(SettingRepository repository) {
        this.repository = repository;
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
                q -> q.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition()));
    }

    public Setting put(String key, String value, String description) {
        Setting setting = repository.findByKey(key)
                .map(existing -> {
                    existing.change(value, description);
                    return existing;
                })
                .orElseGet(() -> Setting.create(key, value, description));
        return repository.save(setting);
    }
}
