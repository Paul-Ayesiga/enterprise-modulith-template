package ug.co.smsone.identity.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PersonContactRepository extends JpaRepository<PersonContact, UUID> {

    /**
     * The directory lookup: who is reachable at this address, best claim first.
     *
     * <p>The ordering is the whole point. A VERIFIED row wins because it is the only one the database
     * guarantees is unambiguous ({@code uq_person_contact_verified_live}); unverified duplicates are
     * legal by design, so "oldest claim wins" would let a stale row nobody ever proved outrank the
     * person who did. {@code createdAt, id} breaks the remaining tie so two calls never disagree.
     *
     * <p>Not restricted to verified rows: the lookup must still FIND an unverified address in order to
     * report it as unverified, which is a different answer from not finding it at all.
     */
    @Query("""
            select c.personId from PersonContact c
            where c.kind = :kind and lower(c.contactValue) = lower(:value)
            order by case when c.verifiedAt is null then 1 else 0 end, c.createdAt asc, c.id asc
            """)
    List<UUID> findPersonIdsByValue(@Param("kind") ContactKind kind, @Param("value") String value, Limit limit);

    /**
     * Every contact of one kind for a page of people, in ONE query. The caller reduces to one address
     * per person: batching here and choosing there keeps the "which address wins" rule in a single
     * place instead of duplicating it into SQL that cannot express it without a window function.
     */
    @Query("select c from PersonContact c where c.personId in :personIds and c.kind = :kind")
    List<PersonContact> findByPersonIds(@Param("personIds") Collection<UUID> personIds,
            @Param("kind") ContactKind kind);
}
