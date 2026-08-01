package ug.co.smsone.profile.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findBySubject(String subject);
}
