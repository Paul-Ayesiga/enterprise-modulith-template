package ug.co.smsone.profile.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PersonPreferenceRepository extends JpaRepository<PersonPreference, PersonPreference.Key> {

    List<PersonPreference> findByPersonIdOrderByPrefKeyAsc(UUID personId);

    /**
     * The submitted keys of ONE person, in ONE round trip — and the reason this method exists instead
     * of {@code findAllById}.
     *
     * <p><b>The trap:</b> {@code findAllById} is the batch form for every OTHER entity in this codebase,
     * and it is not one here. {@code SimpleJpaRepository.findAllById} branches on
     * {@code entityInformation.hasCompositeId()} and, when the id IS composite, falls back to a
     * {@code findById} LOOP — one select per id, which is precisely the N+1 a reader assumes it removed.
     * {@link PersonPreference} is an {@code @IdClass} of (person_id, pref_key), so that branch always
     * wins: a fifty-key PUT was fifty selects. Nothing about the call site reveals this; only the
     * entity's key shape does.
     *
     * <p>Written out as a derived {@code in}, it is a single index scan on
     * {@code person_preference_pkey (person_id, pref_key)} — the same index the loop was hitting, one
     * search instead of N (verified with EXPLAIN: {@code Index Cond: person_id = ? AND pref_key = ANY
     * (...)}, {@code Index Searches: 1}). Every key here shares one person, which is what lets the
     * composite lookup collapse into a leading-column scan.
     */
    List<PersonPreference> findByPersonIdAndPrefKeyIn(UUID personId, Collection<String> prefKeys);
}
