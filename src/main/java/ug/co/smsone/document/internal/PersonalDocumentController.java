package ug.co.smsone.document.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * A user's personal documents (no org). The cross-user reach mirrors the files tiering by blast
 * radius: {@code platform-support} may read another user's document, destroying one takes
 * {@code platform-admin}.
 */
@RestController
@RequestMapping("/api/v1/documents")
class PersonalDocumentController {

    private final DocumentService documents;
    private final OrgDocumentController shared; // the store step and mappers live once

    PersonalDocumentController(DocumentService documents, OrgDocumentController shared) {
        this.documents = documents;
        this.shared = shared;
    }

    @PostMapping
    @Operation(summary = "Upload a personal document")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject upload(@RequestParam("file") MultipartFile file, CurrentUser user) {
        var meta = shared.store(file, "u/" + user.subject(), null, user.subject());
        return OrgDocumentController.toResource(documents.requirePersonal(documents.register(meta)));
    }

    @GetMapping
    @Operation(summary = "List your personal documents")
    WindowedResult<ResourceObject> list(CurrentUser user, CursorPageRequest page) {
        return WindowedResult.of(documents.listPersonal(user.subject(), page), page,
                OrgDocumentController::toResource);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Download a personal document",
            description = "Owner, or platform-support across users. 302 to a presigned URL.")
    ResponseEntity<Void> download(@PathVariable UUID id, CurrentUser user) {
        Document document = documents.requirePersonal(id);
        requireOwnerOr(document, user, PlatformRole.SUPPORT);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(OrgDocumentController.toUri(documents.downloadUrl(document))).build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a personal document",
            description = "Owner, or platform-admin across users — destructive, so the higher tier.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, CurrentUser user) {
        Document document = documents.requirePersonal(id);
        requireOwnerOr(document, user, PlatformRole.ADMIN);
        documents.delete(document);
    }

    private static void requireOwnerOr(Document document, CurrentUser user, String platformTier) {
        if (document.getOwnerSubject().equals(user.subject()) || user.hasRole(platformTier)) {
            return;
        }
        throw new ForbiddenException("You can only access your own documents.");
    }
}
