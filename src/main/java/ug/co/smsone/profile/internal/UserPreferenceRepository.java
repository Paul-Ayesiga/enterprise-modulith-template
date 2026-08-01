package ug.co.smsone.profile.internal;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserPreferenceRepository extends JpaRepository<UserPreference, UserPreference.Key> {

    List<UserPreference> findBySubjectOrderByPrefKeyAsc(String subject);
}
