package ug.co.smsone.profile.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PersonProfileRepository extends JpaRepository<PersonProfile, UUID> {

    /**
     * The person's live profile. {@code uq_person_profile_person_live} makes at most one exist, which
     * is why this returns an Optional rather than a list — a profile is a facet of a person, not a
     * second identity that could have siblings.
     */
    Optional<PersonProfile> findByPersonId(UUID personId);
}
