package ug.co.smsone.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findBySubject(String subject);

    Optional<User> findFirstByEmailIgnoreCaseOrderByProvisionedAtAsc(String email);
}
