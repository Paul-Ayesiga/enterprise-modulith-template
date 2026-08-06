package ug.co.smsone.exchange.internal;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.exchange.ExchangeJobs;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The {@link ExchangeJobs} port: mappings over {@link ExchangeService}, with the caller resolved
 * from the security context exactly as the REST controllers resolve it — the requester on the job
 * row is the honest subject either way ({@code key:<id>} for agents).
 */
@Component
class ExchangeJobsImpl implements ExchangeJobs {

    private final ExchangeService exchange;
    private final HandlerRegistry handlers;
    private final List<FormatCodec> codecs;
    private final CurrentUserProvider currentUser;

    ExchangeJobsImpl(ExchangeService exchange, HandlerRegistry handlers, List<FormatCodec> codecs,
            CurrentUserProvider currentUser) {
        this.exchange = exchange;
        this.handlers = handlers;
        this.codecs = codecs;
        this.currentUser = currentUser;
    }

    @Override
    public List<HandlerView> handlers() {
        List<String> formats = codecs.stream().map(FormatCodec::id).sorted().toList();
        return handlers.all().stream()
                .sorted(Comparator.comparing(ExchangeHandler::id))
                .map(handler -> new HandlerView(handler.id(), handler.importPermission(),
                        handler.exportPermission(), formats))
                .toList();
    }

    @Override
    public WindowedResult<JobView> list(UUID orgId, CursorPageRequest page) {
        return exchange.list(orgId, page, ExchangeJobsImpl::toView);
    }

    @Override
    public JobView get(UUID orgId, UUID jobId) {
        return toView(exchange.require(jobId, orgId));
    }

    @Override
    public JobView submitExport(UUID orgId, String handlerId, String format) {
        return toView(exchange.submitExport(caller(), orgId, handlerId, format));
    }

    @Override
    public JobView cancel(UUID orgId, UUID jobId) {
        return toView(exchange.cancel(caller(), orgId, jobId));
    }

    @Override
    public String resultUrl(UUID orgId, UUID jobId) {
        return exchange.resultUrl(caller(), orgId, jobId).toString();
    }

    @Override
    public String errorReportUrl(UUID orgId, UUID jobId) {
        return exchange.errorReportUrl(caller(), orgId, jobId).toString();
    }

    private CurrentUser caller() {
        return currentUser.requireCurrentUser();
    }

    private static JobView toView(ExchangeJob job) {
        return new JobView(job.id(), job.jobType(), job.handler(), job.format(), job.status(),
                job.processed(), job.failed(), job.attempts(), job.cancelRequested(),
                job.lastError(), job.createdAt());
    }
}
