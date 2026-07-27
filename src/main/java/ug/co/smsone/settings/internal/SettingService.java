package ug.co.smsone.settings.internal;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.NotFoundException;

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

    @Transactional(readOnly = true)
    public List<Setting> all() {
        return repository.findAll(Sort.by("key"));
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
