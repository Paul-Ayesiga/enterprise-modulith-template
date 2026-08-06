package ug.co.smsone.exchange;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Exchange-job port for other protocol surfaces (the MCP module today): discover handlers, submit
 * EXPORTS, poll, cancel, fetch artifact URLs. Imports are deliberately absent — putting bytes in is
 * a multipart REST upload; bulk data never transits MCP (plan §8). This is the platform's async
 * pattern for agents: submit, poll {@code get}, follow the result URL.
 *
 * <p>The caller is resolved from the security context (the requester recorded on the job, the
 * artifact-access rule, and the handler-permission check all key on it) — same as the REST surface.
 */
public interface ExchangeJobs {

    List<HandlerView> handlers();

    WindowedResult<JobView> list(UUID orgId, CursorPageRequest page);

    JobView get(UUID orgId, UUID jobId);

    /** Submit an EXPORT through a handler; enforces the handler's own export permission. */
    JobView submitExport(UUID orgId, String handlerId, String format);

    JobView cancel(UUID orgId, UUID jobId);

    /** A short-lived URL for a COMPLETED export's result file. */
    String resultUrl(UUID orgId, UUID jobId);

    /** A short-lived URL for a job's row-addressed error report, when one exists. */
    String errorReportUrl(UUID orgId, UUID jobId);

    record HandlerView(String id, String importPermission, String exportPermission,
            List<String> formats) {
    }

    record JobView(UUID id, String jobType, String handler, String format, String status,
            long processed, long failed, int attempts, boolean cancelRequested, String lastError,
            Instant createdAt) {
    }
}
