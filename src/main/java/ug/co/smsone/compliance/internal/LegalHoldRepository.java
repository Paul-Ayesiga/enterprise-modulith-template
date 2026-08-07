package ug.co.smsone.compliance.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LegalHoldRepository extends JpaRepository<LegalHold, UUID> {

    boolean existsByPersonIdAndReleasedAtIsNull(UUID personId);

    List<LegalHold> findByReleasedAtIsNullOrderByPlacedAtDesc();

    Optional<LegalHold> findByIdAndReleasedAtIsNull(UUID id);
}
