package ug.co.smsone.compliance.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ErasureRequestRepository extends JpaRepository<ErasureRequest, UUID> {
}
