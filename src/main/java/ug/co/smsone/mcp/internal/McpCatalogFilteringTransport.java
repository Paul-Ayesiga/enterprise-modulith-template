package ug.co.smsone.mcp.internal;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Wraps the servlet transport so the SDK's protocol handler can be decorated — the SDK offers no
 * per-request hook on {@code tools/list}, and the catalog an agent SEES must already be the catalog
 * it may CALL (hiding is a courtesy; {@link McpToolDispatcher}'s re-check is the boundary). The
 * decoration also carries the kill switch: {@code app.mcp.enabled=false} REFUSES every request with
 * an error naming the switch, in the impersonation tradition — routes that vanish look like version
 * skew; a named refusal looks like what it is.
 *
 * <p>The wrapper is what {@code McpServer.sync(...)} builds against; the wrapped servlet transport
 * is what the container serves. {@code setMcpHandler} is the seam where the two meet.
 */
final class McpCatalogFilteringTransport implements McpStatelessServerTransport {

    /** Implementation-defined JSON-RPC error code for "surface administratively disabled". */
    static final int DISABLED_ERROR_CODE = -32050;

    private final HttpServletStatelessServerTransport delegate;
    private final McpToolRegistry registry;
    private final McpAccessPolicy accessPolicy;
    private final McpProperties properties;

    McpCatalogFilteringTransport(HttpServletStatelessServerTransport delegate, McpToolRegistry registry,
            McpAccessPolicy accessPolicy, McpProperties properties) {
        this.delegate = delegate;
        this.registry = registry;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        delegate.setMcpHandler(new FilteringHandler(handler));
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    private final class FilteringHandler implements McpStatelessServerHandler {

        private final McpStatelessServerHandler delegate;

        private FilteringHandler(McpStatelessServerHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
                McpSchema.JSONRPCRequest request) {
            if (!properties.enabled()) {
                return Mono.just(McpSchema.JSONRPCResponse.error(request.id(),
                        new McpSchema.JSONRPCResponse.JSONRPCError(DISABLED_ERROR_CODE,
                                "MCP is disabled on this deployment (app.mcp.enabled=false).")));
            }
            Mono<McpSchema.JSONRPCResponse> response = delegate.handleRequest(context, request);
            if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
                return response.map(r -> filterTools(context, r));
            }
            return response;
        }

        @Override
        public Mono<Void> handleNotification(McpTransportContext context,
                McpSchema.JSONRPCNotification notification) {
            if (!properties.enabled()) {
                return Mono.empty();
            }
            return delegate.handleNotification(context, notification);
        }

        private McpSchema.JSONRPCResponse filterTools(McpTransportContext context,
                McpSchema.JSONRPCResponse response) {
            if (response.error() != null
                    || !(response.result() instanceof McpSchema.ListToolsResult listed)) {
                return response;
            }
            ToolContext caller = McpRequestContextExtractor.toolContext(context);
            List<McpSchema.Tool> visible = listed.tools().stream()
                    .filter(tool -> registry.byName(tool.name())
                            .map(definition -> accessPolicy.mayCall(caller, definition))
                            .orElse(false))
                    .toList();
            return new McpSchema.JSONRPCResponse(response.jsonrpc(), response.id(),
                    new McpSchema.ListToolsResult(visible, listed.nextCursor(), listed.meta()), null);
        }
    }
}
