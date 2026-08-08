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
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * A person's personal documents (no org). The cross-person reach mirrors the files tiering by blast
 * radius: {@code platform-support} may read another person's document, destroying one takes
 * {@code platform-admin}.
 *
 * <h2>Every handler here pins the PLATFORM axis, and it is not decoration</h2>
 *
 * <p>{@code document} is a split table (ADR 0010 §2 row 6) whose entity names no schema, so the
 * {@code search_path} decides which copy a statement reaches — and {@code CurrentUserFilter} pins the
 * caller's organization whenever their token names exactly one, <b>whatever the route</b>. This surface
 * is not under {@code /api/v1/orgs/{orgId}/}, but the overwhelmingly common caller — a human who belongs
 * to a single org — still arrives on that org's axis. Unpinned, {@code POST /api/v1/documents} therefore
 * writes a row with a NULL {@code org_id} into that tenant's schema: the row whose org disagrees with
 * its schema that ADR 0010 §1 calls the worst failure this design can produce. Four things follow, all
 * silent:
 *
 * <ul>
 *   <li><b>It travels.</b> {@code pg_dump -n t_<hex>} is the extraction (§6), and it takes the schema
 *       whole — so a member's private documents leave with an organization that never owned them, which
 *       is the one thing §2.2 says must not happen. The bytes do not follow (their key is
 *       {@code doc/u/<personId>/}, not in the bundle's prefixes), so the extracted deployment gets rows
 *       pointing at objects it does not have.</li>
 *   <li><b>Promotion loses them.</b> The copy plan selects {@code where org_id = ?}, so null-org rows
 *       stay behind in {@code tenant_pool} while the reads move to {@code t_<hex>}.</li>
 *   <li><b>A second organization hides them.</b> A person seated in two orgs resolves to NO org
 *       (§3.3), lands on the platform axis, and cannot see what they uploaded from their first one.</li>
 *   <li><b>The trail splits.</b> {@code AuditLogImpl} routes on the row's own org, so
 *       {@code document.registered} for a null org goes to {@code platform.audit_log} while the document
 *       went to the tenant's copy.</li>
 * </ul>
 *
 * <p>The pin wraps the service call rather than living inside {@code DocumentService}, which is
 * {@code @Transactional}: the schema is chosen when the connection is borrowed and the transaction has
 * already borrowed one, which is why {@code TenantContext.set} throws in there. Same shape and same
 * reason as {@code AdminMaintenanceController.schedule}, which is the mirror image of this bug —
 * an org's row written from a platform-axis request.
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
        UUID owner = requirePerson(user);
        // The whole handler rather than just the register. Only register/requirePersonal touch a table,
        // but one span and one finally is what keeps the pin next to the write it protects — a pin that
        // wraps half a handler is one somebody narrows later without noticing which half mattered.
        return TenantContext.callAsPlatform(() -> {
            var meta = shared.store(file, "u/" + owner, null, owner);
            return OrgDocumentController.toResource(documents.requirePersonal(documents.register(meta)));
        });
    }

    @GetMapping
    @Operation(summary = "List your personal documents")
    WindowedResult<ResourceObject> list(CurrentUser user, CursorPageRequest page) {
        UUID owner = requirePerson(user);
        return TenantContext.callAsPlatform(() -> WindowedResult.of(documents.listPersonal(owner, page),
                page, OrgDocumentController::toResource));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Download a personal document",
            description = "Owner, or platform-support across users. 302 to a presigned URL.")
    ResponseEntity<Void> download(@PathVariable UUID id, CurrentUser user) {
        Document document = TenantContext.callAsPlatform(() -> documents.requirePersonal(id));
        requireOwnerOr(document, user, PlatformRole.SUPPORT);
        // Outside the pin deliberately: presigning is local crypto over a key, and the existence probe
        // is the object store. Neither reads a table, so neither has an axis to get wrong.
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(OrgDocumentController.toUri(documents.downloadUrl(document))).build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a personal document",
            description = "Owner, or platform-admin across users — destructive, so the higher tier.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, CurrentUser user) {
        TenantContext.runAsPlatform(() -> {
            Document document = documents.requirePersonal(id);
            requireOwnerOr(document, user, PlatformRole.ADMIN);
            // Soft-deletes the row AND writes its audit entry; both belong to the platform copy, which
            // is the same span that found the row.
            documents.delete(document);
        });
    }

    /**
     * The owner of a personal document. Refused rather than defaulted for a machine: an API key is
     * not a person, so it has no personal namespace to write into and no personal list to read —
     * and a null owner would violate {@code document.owner_person_id NOT NULL} (V23) at flush,
     * turning a 403's worth of input into a 500.
     */
    private static UUID requirePerson(CurrentUser user) {
        if (user.personId() == null) {
            throw new ForbiddenException("Personal documents belong to a person; this caller is not one.");
        }
        return user.personId();
    }

    private static void requireOwnerOr(Document document, CurrentUser user, String platformTier) {
        if (document.getOwnerPersonId().equals(user.personId()) || user.hasRole(platformTier)) {
            return;
        }
        if (user.hasRole(PlatformRole.SUPPORT)) {
            // A platform tier below the required one can already SEE the document exists (support
            // reads across users), so the honest answer is the real refusal.
            throw new ForbiddenException("This action needs a higher platform tier.");
        }
        // For everyone else 404, not 403: a foreign id must answer exactly like an unknown one —
        // the org surface's rule — or the status difference is an existence oracle for guessed ids.
        throw new ug.co.smsone.shared.error.NotFoundException("Document not found.");
    }
}
