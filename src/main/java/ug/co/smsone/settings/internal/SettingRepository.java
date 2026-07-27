package ug.co.smsone.settings.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface SettingRepository extends JpaRepository<Setting, UUID>, JpaSpecificationExecutor<Setting> {

    Optional<Setting> findByKey(String key);
}
