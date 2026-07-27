package ug.co.smsone;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Generates the committed Modulith documentation (C4 PlantUML diagrams + per-module canvases with
 * published/listened events) into docs/modulith/ — refreshed on every build, like the OpenAPI spec.
 */
@Tag("docs-export")
class DocumentationGenerationTest {

    private static final Path OUTPUT_DIR = Path.of("docs", "modulith");

    @Test
    void generatesModuleDiagramsAndCanvases() {
        ApplicationModules modules = ApplicationModules.of(Application.class);

        new Documenter(modules, Documenter.Options.defaults().withOutputFolder(OUTPUT_DIR.toString()))
                .writeDocumentation();

        assertThat(Files.exists(OUTPUT_DIR.resolve("components.puml")))
                .as("C4 component diagram generated")
                .isTrue();
        assertThat(modules.stream().count()).isGreaterThanOrEqualTo(4); // shared + 4 business modules
    }
}
