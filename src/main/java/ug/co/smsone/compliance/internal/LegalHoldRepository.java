package ug.co.smsone.compliance.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * {@code JpaSpecificationExecutor} is here for one reason: {@code findBy(spec, …)} is the only entry
 * point that accepts a {@code scroll(…)}, and the active-holds listing is keyset-paginated (ADR 0002).
 * The derived {@code findByReleasedAtIsNullOrderByPlacedAtDesc()} it replaced could only ever return
 * the whole collection.
 */
interface LegalHoldRepository extends JpaRepository<LegalHold, UUID>, JpaSpecificationExecutor<LegalHold> {

    boolean existsByPersonIdAndReleasedAtIsNull(UUID personId);

    Optional<LegalHold> findByIdAndReleasedAtIsNull(UUID id);
}
