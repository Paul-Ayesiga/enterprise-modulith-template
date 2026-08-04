package ug.co.smsone.signup.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SignupRepository extends JpaRepository<SignupRequest, UUID> {

    Optional<SignupRequest> findByTokenHashAndStatus(String tokenHash, String status);

    void deleteByEmailAndStatus(String email, String status);
}
