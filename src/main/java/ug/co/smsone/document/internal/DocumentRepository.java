package ug.co.smsone.document.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {
}
