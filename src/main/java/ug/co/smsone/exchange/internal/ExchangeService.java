package ug.co.smsone.exchange.internal;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Submit-side orchestration and job access control. The handler permission cannot go through
 * {@code @PreAuthorize} — the code is chosen at runtime by the submitted handler — so
 * {@link #requirePermission} re-states {@code ApiPermissionEvaluator}'s two rules programmatically:
 * the token must be scoped to THIS org, and the caller must hold the permission in it. Artifacts
 * (source, report, result) are download-gated on the requester or that same handler permission;
 * job METADATA (list/get) needs only {@code org:read}, enforced at the controller.
 */
@Service
class ExchangeService {

    private final ExchangeJobStore store;
    private final HandlerRegistry handlers;
    private final Map<String, FormatCodec> codecs;
    private final ArtifactStore artifacts;
    private final FileStorageProvider storage;
    private final ExchangeProperties config;
    private final ug.co.smsone.shared.audit.AuditLog auditLog;
    private final ug.co.smsone.subscription.Entitlements entitlements;

    ExchangeService(ExchangeJobStore store, HandlerRegistry handlers, List<FormatCodec> codecs,
            ArtifactStore artifacts, FileStorageProvider storage, ExchangeProperties config,
            ug.co.smsone.shared.audit.AuditLog auditLog,
            ug.co.smsone.subscription.Entitlements entitlements) {
        this.store = store;
        this.handlers = handlers;
        this.codecs = codecs.stream()
                .collect(Collectors.toUnmodifiableMap(FormatCodec::id, Function.identity()));
        this.artifacts = artifacts;
        this.storage = storage;
        this.config = config;
        this.auditLog = auditLog;
        this.entitlements = entitlements;
    }

    ExchangeJob submitImport(CurrentUser user, UUID orgId, String handlerId, String format,
            MultipartFile file) {
        ExchangeHandler handler = requireHandler(handlerId);
        String normalizedFormat = requireFormat(format);
        UUID requester = requireRequester(user);
        requirePermission(user, orgId, handler.importPermission());
        entitlements.requireFeature(orgId, ug.co.smsone.subscription.EntitlementKeys.EXCHANGE_ENABLED);
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Uploaded file is empty.", ApiSource.parameter("file"));
        }
        String name = ArtifactStore.safeFileName(file.getOriginalFilename(),
                handler.id() + "-import." + codecs.get(normalizedFormat).fileExtension());
        // The key gets its own uuid (the job id does not exist yet); result/report keys use the job id.
        String key = "exch/o/" + orgId + "/" + UUID.randomUUID() + "/" + name;
        try (java.io.InputStream in = file.getInputStream()) {
            artifacts.store(key, in, file.getSize(),
                    codecs.get(normalizedFormat).contentType(), name, orgId, requester);
        } catch (IOException ex) {
            throw new ValidationException("Could not read the uploaded file.", ApiSource.parameter("file"));
        }
        UUID id = store.submit(orgId, requester, ExchangeJob.IMPORT, handler.id(),
                handler.templateVersion(), normalizedFormat, key);
        auditLog.record("exchange.job_submitted", orgId, id.toString(), null,
                "type=IMPORT handler=" + handler.id() + " format=" + normalizedFormat);
        return require(id, orgId);
    }

    ExchangeJob submitExport(CurrentUser user, UUID orgId, String handlerId, String format) {
        ExchangeHandler handler = requireHandler(handlerId);
        String normalizedFormat = requireFormat(format);
        UUID requester = requireRequester(user);
        requirePermission(user, orgId, handler.exportPermission());
        entitlements.requireFeature(orgId, ug.co.smsone.subscription.EntitlementKeys.EXCHANGE_ENABLED);
        UUID id = store.submit(orgId, requester, ExchangeJob.EXPORT, handler.id(),
                handler.templateVersion(), normalizedFormat, null);
        auditLog.record("exchange.job_submitted", orgId, id.toString(), null,
                "type=EXPORT handler=" + handler.id() + " format=" + normalizedFormat);
        return require(id, orgId);
    }

    <T> WindowedResult<T> list(UUID orgId, CursorPageRequest page, Function<ExchangeJob, T> mapper) {
        return store.list(orgId, page, mapper);
    }

    ExchangeJob require(UUID id, UUID orgId) {
        return store.find(id, orgId)
                .orElseThrow(() -> new NotFoundException("Exchange job not found."));
    }

    ExchangeJob cancel(CurrentUser user, UUID orgId, UUID id) {
        ExchangeJob job = require(id, orgId);
        requireArtifactAccess(user, job);
        if (!store.requestCancel(id, orgId)) {
            throw new ConflictException("The job has already finished; there is nothing to cancel.");
        }
        auditLog.record("exchange.job_cancel_requested", orgId, id.toString(), job.status(), null);
        return require(id, orgId);
    }

    URL errorReportUrl(CurrentUser user, UUID orgId, UUID id) {
        ExchangeJob job = require(id, orgId);
        requireArtifactAccess(user, job);
        if (job.errorReportKey() == null || !storage.exists(job.errorReportKey())) {
            throw new NotFoundException("This job has no error report.");
        }
        return storage.presignGet(job.errorReportKey(), config.presignTtl());
    }

    URL resultUrl(CurrentUser user, UUID orgId, UUID id) {
        ExchangeJob job = require(id, orgId);
        requireArtifactAccess(user, job);
        if (job.resultKey() == null || !storage.exists(job.resultKey())) {
            throw new NotFoundException("This job has no result file.");
        }
        return storage.presignGet(job.resultKey(), config.presignTtl());
    }

    ExchangeHandler requireHandler(String handlerId) {
        if (handlerId == null || handlerId.isBlank()) {
            throw new ValidationException("handler is required.", ApiSource.parameter("handler"));
        }
        return handlers.find(handlerId.trim())
                .orElseThrow(() -> new NotFoundException("No exchange handler named '"
                        + handlerId.trim() + "'. GET /api/v1/exchange/handlers lists the available ones."));
    }

    String requireFormat(String format) {
        String normalized = format == null ? "" : format.trim().toUpperCase();
        if (!codecs.containsKey(normalized)) {
            throw new ValidationException("format must be one of: "
                    + codecs.keySet().stream().sorted().collect(Collectors.joining(", ")),
                    ApiSource.parameter("format"));
        }
        return normalized;
    }

    /**
     * The person a deferred job will run as. A machine is refused HERE rather than at the database:
     * {@code exchange_job.requester_person_id} and {@code exchange_schedule.requester_person_id} are
     * both NOT NULL (V24, V25) because the job outlives the request that submitted it and its
     * authority is re-resolved per record at processing time. An API key has nothing to re-resolve —
     * its minted subset is frozen at creation and no membership change revokes it — so the guard the
     * column exists for could not run. Letting the null reach the insert would turn a 403's worth of
     * input into a constraint-violation 500.
     */
    UUID requireRequester(CurrentUser user) {
        if (user.personId() == null) {
            throw new ForbiddenException("Submitting an exchange job needs a signed-in person: the job "
                    + "re-checks its requester's permissions when it runs, and a machine key has none to re-check.");
        }
        return user.personId();
    }

    void requirePermission(CurrentUser user, UUID orgId, String permissionCode) {
        if (user.organizationId() == null || !user.organizationId().equals(orgId)) {
            throw new ForbiddenException("Your token is not scoped to this organization.");
        }
        // One check for both caller shapes. A person's membership-derived set and a machine key's
        // minted subset both arrive as CurrentUser.permissions() scoped to CurrentUser.organizationId(),
        // resolved once at the edge — so the machine branch that used to read the token directly is
        // gone, and with it the chance of the two answers drifting apart.
        if (!user.hasPermission(orgId, permissionCode)) {
            throw new ForbiddenException("You need the '" + permissionCode
                    + "' permission to use this exchange handler.");
        }
    }

    /**
     * Artifacts carry the data itself: only the requester or a holder of the handler permission.
     *
     * <p>The job's requester leads the comparison because it is NOT NULL (V24) while
     * {@code user.personId()} is null for a machine — a robot is nobody's requester, so it falls
     * through to the permission check its minted subset can still answer.
     */
    private void requireArtifactAccess(CurrentUser user, ExchangeJob job) {
        if (job.requesterPersonId().equals(user.personId())) {
            return;
        }
        ExchangeHandler handler = handlers.find(job.handler())
                .orElseThrow(() -> new ForbiddenException(
                        "Only the requester can access this job's artifacts."));
        requirePermission(user, job.orgId(), ExchangeJob.EXPORT.equals(job.jobType())
                ? handler.exportPermission() : handler.importPermission());
    }
}
