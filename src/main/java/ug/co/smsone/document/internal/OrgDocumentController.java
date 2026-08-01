package ug.co.smsone.document.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ug.co.smsone.shared.document.NewDocument;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * An organization's documents, gated on the {@code document:read} / {@code document:manage} pair.
 * Download is the files pattern: a 302 to a short-lived presigned URL — bytes never pass through
 * this API.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/documents")
class OrgDocumentController {

    static final String RESOURCE_TYPE = "document";

    private final DocumentService documents;
    private final FileStorageProvider storage;

    OrgDocumentController(DocumentService documents, FileStorageProvider storage) {
        this.documents = documents;
        this.storage = storage;
    }

    record DocumentAttributes(String name, String contentType, long sizeBytes, String source,
            String orgId, String ownerSubject) {
    }

    @PostMapping
    @Operation(summary = "Upload a document into the organization",
            description = """
                    Sent as `multipart/form-data` under `file`. The object key is minted server-side \
                    in the document namespace; the returned resource id addresses every later \
                    download and delete.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'document:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject upload(@PathVariable UUID orgId, @RequestParam("file") MultipartFile file,
            CurrentUser user) {
        NewDocument meta = store(file, "o/" + orgId, orgId, user.subject());
        return toResource(documents.requireInOrg(orgId, documents.register(meta)));
    }

    @GetMapping
    @Operation(summary = "List the organization's documents")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'document:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(documents.listForOrg(orgId, page), page, OrgDocumentController::toResource);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Download a document",
            description = "302 to a short-lived presigned URL; follow the redirect for the bytes.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'document:read')")
    ResponseEntity<Void> download(@PathVariable UUID orgId, @PathVariable UUID id) {
        Document document = documents.requireInOrg(orgId, id);
        return ResponseEntity.status(HttpStatus.FOUND).location(toUri(documents.downloadUrl(document))).build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document",
            description = "The bytes are removed immediately; the metadata row soft-remains as the trail.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'document:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        documents.delete(documents.requireInOrg(orgId, id));
    }

    /**
     * Shared store step: mint the key, VALIDATE the metadata, then push the bytes. The metadata is
     * built before the upload on purpose — {@code NewDocument}'s length checks throwing after the
     * put would orphan an unreachable object on every over-long filename, and turn a 422's worth
     * of input into a 500.
     */
    NewDocument store(MultipartFile file, String namespace, UUID orgId, String ownerSubject) {
        if (file.isEmpty()) {
            throw new ValidationException("Uploaded file is empty.", ApiSource.parameter("file"));
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "document" : file.getOriginalFilename();
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 200) {
            throw new ValidationException("File name is too long (200 characters max).",
                    ApiSource.parameter("file"));
        }
        String key = "doc/" + namespace + "/" + UUID.randomUUID() + "/" + safe;
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        NewDocument meta = new NewDocument(orgId, ownerSubject, key, safe, contentType,
                file.getSize(), "UPLOAD");
        try (java.io.InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), contentType);
        } catch (java.io.IOException ex) {
            throw new ValidationException("Could not read the uploaded file.", ApiSource.parameter("file"));
        }
        return meta;
    }

    static ResourceObject toResource(Document document) {
        return new ResourceObject(document.getId().toString(), RESOURCE_TYPE,
                new DocumentAttributes(document.getName(), document.getContentType(),
                        document.getSizeBytes(), document.getSource(),
                        document.getOrgId() == null ? null : document.getOrgId().toString(),
                        document.getOwnerSubject()));
    }

    static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Storage returned a malformed presigned URL", ex);
        }
    }
}
