package ug.co.smsone.settings.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SettingRepository extends JpaRepository<Setting, UUID> {

    Optional<Setting> findByKey(String key);
}
