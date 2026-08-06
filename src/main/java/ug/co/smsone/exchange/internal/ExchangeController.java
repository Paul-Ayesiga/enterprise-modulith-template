package ug.co.smsone.exchange.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The exchange surface: submit is a 202 (the work is a background job, never the request), progress
 * is polled on the job resource, artifacts download as 302s to short-lived presigned URLs. Listing
 * and reading job metadata needs {@code org:read}; submitting and downloading are additionally
 * gated on the handler's own permission inside {@link ExchangeService}.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/exchange")
class ExchangeController {

    static final String RESOURCE_TYPE = "exchange-job";

    private final ExchangeService exchange;

    ExchangeController(ExchangeService exchange) {
        this.exchange = exchange;
    }

    record JobAttributes(String jobType, String handler, int handlerVersion, String format,
            String status, long processed, long failed, String requester, boolean cancelRequested,
            String lastError, Instant createdAt) {
    }

    record ExportRequest(String handler, String format) {
    }

    @PostMapping("/imports")
    @Operation(summary = "Submit an import job",
            description = """
                    Sent as `multipart/form-data`: the source under `file`, plus `handler` and \
                    `format` (CSV or JSONL) parameters. Answers 202 immediately — processing is a \
                    durable background job; poll the returned resource for progress. The submitter \
                    must hold the handler's import permission.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResourceObject submitImport(@PathVariable UUID orgId, @RequestParam("handler") String handler,
            @RequestParam(name = "format", defaultValue = "CSV") String format,
            @RequestParam("file") MultipartFile file, CurrentUser user) {
        return toResource(exchange.submitImport(user, orgId, handler, format, file));
    }

    @PostMapping("/exports")
    @Operation(summary = "Submit an export job",
            description = """
                    Answers 202; when the job completes, its result downloads via \
                    `GET /jobs/{id}/result`. The submitter must hold the handler's export permission.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResourceObject submitExport(@PathVariable UUID orgId, @RequestBody ExportRequest request,
            CurrentUser user) {
        return toResource(exchange.submitExport(user, orgId, request.handler(), request.format()));
    }

    @GetMapping("/jobs")
    @Operation(summary = "List the organization's exchange jobs")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return exchange.list(orgId, page, ExchangeController::toResource);
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "Get one exchange job with its progress")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable UUID id) {
        return toResource(exchange.require(id, orgId));
    }

    @PostMapping("/jobs/{id}/cancel")
    @Operation(summary = "Request cancellation of a job",
            description = """
                    Best-effort: a PENDING job never starts; a running import stops at its next \
                    batch boundary (already-committed records stay applied); an export already in \
                    flight completes.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    ResourceObject cancel(@PathVariable UUID orgId, @PathVariable UUID id, CurrentUser user) {
        return toResource(exchange.cancel(user, orgId, id));
    }

    @GetMapping("/jobs/{id}/report")
    @Operation(summary = "Download a job's row-addressed error report",
            description = "302 to a short-lived presigned URL. CSV with `row_number,error` columns.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    ResponseEntity<Void> report(@PathVariable UUID orgId, @PathVariable UUID id, CurrentUser user) {
        return redirect(exchange.errorReportUrl(user, orgId, id));
    }

    @GetMapping("/jobs/{id}/result")
    @Operation(summary = "Download a completed export's result file",
            description = "302 to a short-lived presigned URL; follow the redirect for the bytes.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    ResponseEntity<Void> result(@PathVariable UUID orgId, @PathVariable UUID id, CurrentUser user) {
        return redirect(exchange.resultUrl(user, orgId, id));
    }

    static ResourceObject toResource(ExchangeJob job) {
        return new ResourceObject(job.id().toString(), RESOURCE_TYPE,
                new JobAttributes(job.jobType(), job.handler(), job.handlerVersion(), job.format(),
                        job.status(), job.processed(), job.failed(), job.requester(),
                        job.cancelRequested(), job.lastError(), job.createdAt()));
    }

    private static ResponseEntity<Void> redirect(URL url) {
        try {
            return ResponseEntity.status(HttpStatus.FOUND).location(url.toURI()).build();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Storage returned a malformed presigned URL", ex);
        }
    }
}
