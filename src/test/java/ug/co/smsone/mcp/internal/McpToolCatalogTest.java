package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Pins the catalog contract every future manifest must honor (plan §7): complete metadata, a
 * permission that exists in the REST vocabulary, and safety annotations that tell the truth on the
 * wire. The registry fail-fasts at boot on most of these; this test is what fails a REVIEW with the
 * reason spelled out when someone weakens that.
 */
class McpToolCatalogTest extends AbstractIntegrationTest {

    @Autowired
    private McpToolRegistry registry;

    @Test
    void everyToolDeclaresCompleteMetadataAndARealPermission() {
        assertThat(registry.all()).isNotEmpty();
        for (ToolDefinition tool : registry.all()) {
            assertThat(tool.name()).matches("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");
            assertThat(tool.version()).isGreaterThanOrEqualTo(1);
            assertThat(tool.tag()).isNotBlank();
            assertThat(tool.title()).isNotBlank();
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).containsEntry("type", "object");
            if (tool.requiredPermission() != null) {
                assertThatCode(() -> Permission.fromCode(tool.requiredPermission()))
                        .as("tool %s must gate on a code from organization.Permission", tool.name())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void theWireShapeCarriesTheSafetyAnnotationsAndVersionMeta() {
        for (ToolDefinition tool : registry.all()) {
            McpSchema.Tool wire = McpToolRegistry.toMcpTool(tool);
            assertThat(wire.annotations().readOnlyHint())
                    .isEqualTo(tool.kind() == ToolDefinition.Kind.READ);
            assertThat(wire.annotations().destructiveHint())
                    .as("DESTRUCTIVE tools must say so on the wire; others must not cry wolf")
                    .isEqualTo(tool.kind() == ToolDefinition.Kind.DESTRUCTIVE);
            assertThat(wire.meta())
                    .containsEntry("smsone/toolVersion", tool.version())
                    .containsEntry("smsone/area", tool.tag());
        }
    }

    @Test
    void theFoundationShipsWhoamiCallableByAnyAuthenticatedPrincipal() {
        assertThat(registry.byName("whoami")).hasValueSatisfying(tool -> {
            assertThat(tool.kind()).isEqualTo(ToolDefinition.Kind.READ);
            assertThat(tool.requiredPermission()).isNull();
        });
    }
}
