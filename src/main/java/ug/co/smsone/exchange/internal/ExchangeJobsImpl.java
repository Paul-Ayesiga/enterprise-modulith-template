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
 * from the security context exactly as the REST controllers resolve it.
 *
 * <p>READS work for any caller. SUBMIT — and only submit — needs a PERSON: an API-key agent is refused
 * by {@code ExchangeService.requireRequester} while an OAuth agent (which resolves to a person like any
 * other sign-in) is not. That is the schema's rule, not this port's: {@code requester_person_id} is NOT
 * NULL because the job outlives the request and re-checks its requester's permissions when it runs, and
 * a machine key has no membership to re-check.
 *
 * <p>CANCEL is NOT in that set, which reads like an inconsistency and is not. It goes through
 * {@code ExchangeService.requireArtifactAccess}, whose requester comparison a machine simply loses — a
 * robot is nobody's requester — so it falls through to the handler permission its minted subset CAN
 * answer. Nothing is written that needs a person to name: cancelling only flips a flag on a row whose
 * requester already exists. So a key holding {@code exchange:submit} plus the handler's own permission
 * can cancel a job it could never have submitted, and the MCP tests pin exactly that. Do not "fix" the
 * asymmetry by routing cancel through {@code requireRequester}.
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
