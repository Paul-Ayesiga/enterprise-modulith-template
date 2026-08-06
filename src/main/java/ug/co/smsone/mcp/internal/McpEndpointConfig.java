package ug.co.smsone.mcp.internal;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mounts the MCP server at {@code /mcp}: the SDK's STATELESS streamable-HTTP servlet — plain
 * JSON-RPC request/response, no {@code Mcp-Session-Id}, no SSE stream, no in-memory session map —
 * which is what makes the surface safe across N replicas with no sticky routing (plan §1). Being a
 * servlet (not an MVC controller), the JSON:API envelope advice and springdoc never see it; being
 * inside the servlet chain, request-id, API-key auth and rate limiting already do.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
class McpEndpointConfig {

    static final String ENDPOINT = "/mcp";

    @Bean
    HttpServletStatelessServerTransport mcpServletTransport(McpRequestContextExtractor contextExtractor) {
        return HttpServletStatelessServerTransport.builder()
                .messageEndpoint(ENDPOINT)
                .contextExtractor(contextExtractor)
                .build();
    }

    /**
     * Built against the FILTERING wrapper, not the raw transport — that seam is where the kill
     * switch and the permission-filtered {@code tools/list} live. {@code immediateExecution} keeps
     * handlers on the request thread: tool work is short port calls, and the thread already carries
     * what downstream code expects (the dispatcher still installs the caller's context defensively).
     */
    @Bean(destroyMethod = "closeGracefully")
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, McpToolRegistry registry,
            McpAccessPolicy accessPolicy, McpToolDispatcher dispatcher, McpPromptCatalog prompts,
            McpResourceCatalog resources, McpProperties properties, ObjectProvider<BuildProperties> build) {
        BuildProperties buildProperties = build.getIfAvailable();
        return McpServer.sync(new McpCatalogFilteringTransport(transport, registry, accessPolicy, properties))
                .serverInfo("smsone-platform", buildProperties == null ? "dev" : buildProperties.getVersion())
                .instructions("""
                        SMS One platform tools. Every tool operates on the organization your API key \
                        belongs to — there is no organization argument, and you only see tools your \
                        key's permissions allow. Results carry a requestId (also in _meta) to quote \
                        to support. Collection tools paginate with page_size/page_after cursors. \
                        Read smsone://guide/agent before non-trivial work.""")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .prompts(false)
                        .resources(false, false)
                        .build())
                .immediateExecution(true)
                .tools(dispatcher.toolSpecifications())
                .prompts(prompts.specifications())
                .resources(resources.specifications())
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
            HttpServletStatelessServerTransport transport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport, ENDPOINT);
        registration.setName("mcp");
        registration.setAsyncSupported(true);
        return registration;
    }

}
