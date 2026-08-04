package ug.co.smsone.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Exports the OpenAPI 3.0.1 spec to docs/openapi/ (committed; imports into Postman AND stricter
 * clients like HTTPie, which reject 3.1) and doubles as the
 * API-contract smoke check. Runs in the normal build and via {@code ./gradlew exportOpenApi}.
 */
@Tag("openapi-export")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class OpenApiExportTest extends AbstractIntegrationTest {

    private static final Path OUTPUT_DIR = Path.of("docs", "openapi");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void exportsOpenApiSpec() throws Exception {
        String yaml = restTemplate.getForObject("/v3/api-docs.yaml", String.class);
        String json = restTemplate.getForObject("/v3/api-docs", String.class);

        assertThat(yaml).isNotBlank().contains("openapi: 3.0.1"); // 3.1 breaks HTTPie-class importers
        assertThat(json).isNotBlank().contains("\"openapi\"");
        assertThat(yaml).contains("bearerAuth").contains("keycloak");

        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve("openapi.yaml"), yaml);
        Files.writeString(OUTPUT_DIR.resolve("openapi.json"), json);
    }
}
