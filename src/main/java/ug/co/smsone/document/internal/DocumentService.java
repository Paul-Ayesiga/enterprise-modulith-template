package ug.co.smsone.document.internal;

import java.net.URL;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import ug.co.smsone.document.DocumentRegistered;
import ug.co.smsone.shared.document.Documents;
import ug.co.smsone.shared.document.NewDocument;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.search.SearchDoc;
import ug.co.smsone.search.SearchIndex;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * The catalog's lifecycle, and the {@link Documents} port other modules register artifacts through.
 * Storage stays behind the files port; deletion is asymmetric by design — the OBJECT goes first
 * (remote, outside the transaction, §4.3), then the row soft-deletes with its audit row in one
 * transaction. A crash between the two leaves a row whose download 404s and whose retry finishes
 * the job; the reverse order would leave orphaned bytes nothing can reach.
 */
@Service
class DocumentService implements Documents {

    static final String SEARCH_TYPE = "document";

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final DocumentRepository documents;
    private final FileStorageProvider storage;
    private final SearchIndex searchIndex;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactionTemplate;
    private final DocumentProperties properties;

    DocumentService(DocumentRepository documents, FileStorageProvider storage, SearchIndex searchIndex,
            AuditLog auditLog, ApplicationEventPublisher events, TransactionTemplate transactionTemplate,
            DocumentProperties properties) {
        this.documents = documents;
        this.storage = storage;
        this.searchIndex = searchIndex;
        this.auditLog = auditLog;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    @Override
    @Transactional
    public UUID register(NewDocument meta) {
        Document saved = documents.save(Document.register(meta));
        searchIndex.upsert(new SearchDoc(meta.orgId(), SEARCH_TYPE, saved.getId().toString(),
                meta.name(), meta.name()));
        auditLog.record("document.registered", meta.orgId(), saved.getId().toString(), null,
                meta.name() + " (" + meta.source() + ")");
        // Explicit publish: the id is persist-assigned, so an aggregate-registered event could not
        // have carried it (see Document.register).
        events.publishEvent(new DocumentRegistered(saved.getId(), meta.orgId(), meta.name(),
                meta.source(), Instant.now()));
        return saved.getId();
    }

    @Transactional(readOnly = true)
    Window<Document> listForOrg(UUID orgId, CursorPageRequest page) {
        return documents.findBy((root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                q -> q.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
    }

    @Transactional(readOnly = true)
    Window<Document> listPersonal(String ownerSubject, CursorPageRequest page) {
        return documents.findBy((root, query, cb) -> cb.and(
                        cb.isNull(root.get("orgId")),
                        cb.equal(root.get("ownerSubject"), ownerSubject)),
                q -> q.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
    }

    @Transactional(readOnly = true)
    Document requireInOrg(UUID orgId, UUID id) {
        return documents.findById(id)
                .filter(document -> orgId.equals(document.getOrgId())) // never resolve across tenants
                .orElseThrow(() -> new NotFoundException("Document not found."));
    }

    @Transactional(readOnly = true)
    Document requirePersonal(UUID id) {
        return documents.findById(id)
                .filter(document -> document.getOrgId() == null)
                .orElseThrow(() -> new NotFoundException("Document not found."));
    }

    URL downloadUrl(Document document) {
        return storage.presignGet(document.getStorageKey(), properties.presignTtl());
    }

    /** Object first (remote, idempotent), then row + audit + un-indexing in one transaction. */
    void delete(Document document) {
        storage.delete(document.getStorageKey());
        transactionTemplate.executeWithoutResult(tx -> {
            documents.delete(document); // soft: @SQLDelete stamps deleted_at
            searchIndex.remove(SEARCH_TYPE, document.getId().toString());
            auditLog.record("document.deleted", document.getOrgId(), document.getId().toString(),
                    document.getName(), null);
        });
    }
}
