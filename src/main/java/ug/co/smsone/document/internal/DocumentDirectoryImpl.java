package ug.co.smsone.document.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.document.DocumentDirectory;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/** The {@link DocumentDirectory} port: org-scoped mappings over {@link DocumentService}. */
@Component
class DocumentDirectoryImpl implements DocumentDirectory {

    private final DocumentService documents;

    DocumentDirectoryImpl(DocumentService documents) {
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public WindowedResult<DocumentView> list(UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(documents.listForOrg(orgId, page), page, DocumentDirectoryImpl::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentView get(UUID orgId, UUID documentId) {
        return toView(documents.requireInOrg(orgId, documentId));
    }

    @Override
    @Transactional(readOnly = true)
    public String downloadUrl(UUID orgId, UUID documentId) {
        return documents.downloadUrl(documents.requireInOrg(orgId, documentId)).toString();
    }

    @Override
    public void delete(UUID orgId, UUID documentId) {
        documents.delete(documents.requireInOrg(orgId, documentId));
    }

    private static DocumentView toView(Document document) {
        return new DocumentView(document.getId(), document.getName(), document.getContentType(),
                document.getSizeBytes(), document.getSource(), document.getCreatedAt());
    }
}
