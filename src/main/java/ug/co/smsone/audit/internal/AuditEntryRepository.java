package ug.co.smsone.audit.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID>, JpaSpecificationExecutor<AuditEntry> {
}
