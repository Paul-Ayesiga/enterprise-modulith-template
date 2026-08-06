package ug.co.smsone.mcp.internal.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.exchange.ExchangeJobs;
import ug.co.smsone.mcp.internal.ToolDefinition;
import ug.co.smsone.mcp.internal.ToolDefinition.Kind;
import ug.co.smsone.mcp.internal.ToolManifest;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Exchange tools — the async pattern for agents: discover handlers, submit an EXPORT, poll the job,
 * follow the result URL. Reads need {@code exchange:read}, submit/cancel need {@code
 * exchange:submit} (the REST rule); each handler additionally enforces its OWN export permission at
 * submit, so the gate an agent sees in tools/list is the floor, not the whole story — the
 * description says so.
 */
@Component
class ExchangeToolManifest implements ToolManifest {

    private final ExchangeJobs exchange;

    ExchangeToolManifest(ExchangeJobs exchange) {
        this.exchange = exchange;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ToolDefinition("exchange_handlers", "List exchange handlers",
                        "The datasets the exchange platform can move: each handler's id, the "
                                + "permission its exports require, and the supported formats.",
                        1, "exchange", Kind.READ, "exchange:read",
                        ToolDefinition.noArguments(),
                        (context, args) -> Map.of("handlers", exchange.handlers())),

                new ToolDefinition("exchange_jobs_list", "List exchange jobs",
                        "This organization's import/export jobs, newest first (status, progress "
                                + "counts, errors).",
                        1, "exchange", Kind.READ, "exchange:read",
                        ToolArgs.schema(ToolArgs.pageProperties()),
                        (context, args) -> exchange.list(context.orgId(), ToolArgs.page(args))),

                new ToolDefinition("exchange_job_get", "Get exchange job",
                        "Poll one job's status and progress. Terminal statuses: COMPLETED, "
                                + "COMPLETED_WITH_ERRORS, FAILED, CANCELLED.",
                        1, "exchange", Kind.READ, "exchange:read",
                        ToolArgs.schema(Map.of("job_id", ToolArgs.string("The job id.")), "job_id"),
                        (context, args) -> exchange.get(context.orgId(), id(args))),

                new ToolDefinition("exchange_submit", "Submit export job",
                        "Submit an EXPORT through a handler (see exchange_handlers). Asynchronous: "
                                + "returns the queued job — poll exchange_job_get, then fetch "
                                + "exchange_result_url when COMPLETED. The handler's own export "
                                + "permission is enforced at submit. Imports are not available over "
                                + "MCP (file upload is a REST surface).",
                        1, "exchange", Kind.WRITE, "exchange:submit",
                        ToolArgs.schema(submitProperties(), "handler"),
                        (context, args) -> exchange.submitExport(context.orgId(),
                                ToolArgs.requireString(args, "handler"),
                                ToolArgs.optionalString(args, "format"))),

                new ToolDefinition("exchange_cancel", "Cancel exchange job",
                        "Request cancellation of a queued or running job. Finished jobs answer a "
                                + "conflict.",
                        1, "exchange", Kind.WRITE, "exchange:submit",
                        ToolArgs.schema(Map.of("job_id", ToolArgs.string("The job id.")), "job_id"),
                        (context, args) -> exchange.cancel(context.orgId(), id(args))),

                new ToolDefinition("exchange_result_url", "Export result URL",
                        "Mint a short-lived download URL for a COMPLETED export's result file.",
                        1, "exchange", Kind.READ, "exchange:read",
                        ToolArgs.schema(Map.of("job_id", ToolArgs.string("The job id.")), "job_id"),
                        (context, args) -> Map.of("url",
                                exchange.resultUrl(context.orgId(), id(args)))),

                new ToolDefinition("exchange_error_report_url", "Error report URL",
                        "Mint a short-lived download URL for a job's row-addressed error report, "
                                + "when one exists.",
                        1, "exchange", Kind.READ, "exchange:read",
                        ToolArgs.schema(Map.of("job_id", ToolArgs.string("The job id.")), "job_id"),
                        (context, args) -> Map.of("url",
                                exchange.errorReportUrl(context.orgId(), id(args)))));
    }

    private static UUID id(Map<String, Object> args) {
        try {
            return UUID.fromString(ToolArgs.requireString(args, "job_id"));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("'job_id' must be a UUID.", ApiSource.pointer("/job_id"));
        }
    }

    private static Map<String, Object> submitProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("handler", ToolArgs.string("The handler id (see exchange_handlers)."));
        properties.put("format", ToolArgs.string("Output format (csv, jsonl, xlsx, xml); handler default when omitted."));
        return properties;
    }
}
