package ug.co.smsone.identity.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findBySubject(String subject);

    Optional<User> findFirstByEmailIgnoreCaseOrderByProvisionedAtAsc(String email);

    List<User> findBySubjectIn(Collection<String> subjects);

    /**
     * Native, because {@code @SQLRestriction} hides the very rows this asks about: to every HQL query
     * a deleted account and an account that never existed are the same absence, and they must not lead
     * to the same access decision.
     */
    @Query(value = "select exists(select 1 from app_user where subject = :subject and deleted_at is not null)",
            nativeQuery = true)
    boolean existsDeletedBySubject(@Param("subject") String subject);
}
