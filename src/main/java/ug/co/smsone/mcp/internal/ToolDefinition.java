package ug.co.smsone.mcp.internal;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * One tool in the catalog: the wire contract (name, schema, metadata) plus its handler. Handlers
 * are pure delegations to module ports — authorization, guards, audit and metrics all happen in
 * {@link McpToolDispatcher}, never here.
 *
 * <p>Evolution rule (plan §1): a tool is never renamed. A breaking schema change bumps
 * {@code version} and says so in the description; additive changes don't.
 *
 * @param name               {@code <area>_<verb>} snake_case, unique, permanent
 * @param title              human title clients show in pickers
 * @param description        what the tool does and returns — written for the agent
 * @param version            starts at 1; bumped only on breaking change
 * @param tag                the module area ({@code foundation}, {@code organization}, …)
 * @param kind               safety class — maps onto the spec's read-only/destructive annotations
 * @param requiredPermission an {@code organization.Permission} code, or null for any authenticated
 *                           caller (the {@code whoami} case)
 * @param inputSchema        JSON Schema for the arguments (draft 2020-12, additionalProperties off)
 * @param handler            {@code (context, arguments) -> payload}; the payload becomes the
 *                           result's structured content
 */
public record ToolDefinition(String name, String title, String description, int version, String tag,
        Kind kind, String requiredPermission, Map<String, Object> inputSchema,
        BiFunction<ToolContext, Map<String, Object>, Object> handler) {

    public enum Kind {
        READ, WRITE, DESTRUCTIVE;

        public boolean mutates() {
            return this != READ;
        }
    }

    /** An argument-less tool's schema — the empty closed object. */
    public static Map<String, Object> noArguments() {
        return Map.of("type", "object", "additionalProperties", false);
    }
}
