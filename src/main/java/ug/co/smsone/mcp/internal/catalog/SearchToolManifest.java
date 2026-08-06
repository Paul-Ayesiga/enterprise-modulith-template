package ug.co.smsone.mcp.internal.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ug.co.smsone.mcp.internal.ToolDefinition;
import ug.co.smsone.mcp.internal.ToolDefinition.Kind;
import ug.co.smsone.mcp.internal.ToolManifest;
import ug.co.smsone.search.SearchQueries;

/**
 * Search — one tool, the org-scoped ranked query. Indexing has no tool on purpose: producers index
 * through their own module events, and a platform-wide search stays an admin surface.
 */
@Component
class SearchToolManifest implements ToolManifest {

    private final SearchQueries search;

    SearchToolManifest(SearchQueries search) {
        this.search = search;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(new ToolDefinition("search_query", "Search the organization",
                "Ranked full-text search across this organization's indexed records "
                        + "(websearch syntax: quoted phrases, -exclusions, or). A trigram pass "
                        + "catches prefixes and typos when nothing token-matches. Optional "
                        + "entity_type narrows to one record kind.",
                1, "search", Kind.READ, "search:query",
                ToolArgs.schema(properties(), "q"),
                (context, args) -> search.search(context.orgId(),
                        ToolArgs.requireString(args, "q"),
                        ToolArgs.optionalString(args, "entity_type"),
                        ToolArgs.page(args))));
    }

    private static Map<String, Object> properties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("q", ToolArgs.string("The query (websearch syntax)."));
        properties.put("entity_type", ToolArgs.string("Optional entity type filter."));
        properties.putAll(ToolArgs.pageProperties());
        return properties;
    }
}
