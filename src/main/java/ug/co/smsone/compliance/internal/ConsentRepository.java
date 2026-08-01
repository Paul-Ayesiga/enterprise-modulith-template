package ug.co.smsone.compliance.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ConsentRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findBySubjectOrderByCreatedAtDesc(String subject);
}
