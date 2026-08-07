package ug.co.smsone.profile.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PersonPreferenceRepository extends JpaRepository<PersonPreference, PersonPreference.Key> {

    List<PersonPreference> findByPersonIdOrderByPrefKeyAsc(UUID personId);
}
