package ug.co.smsone.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The published spec must describe the parameters the server actually binds.
 *
 * <p>springdoc cannot see through {@link CursorPageRequestArgumentResolver}, so left alone it documents
 * the handler's {@code CursorPageRequest page} argument as a single object-typed query parameter called
 * {@code page}. Generated clients then send names the resolver never reads — Postman flattens that
 * object to {@code size=…&after=…} — and paging silently does nothing: the request returns the DEFAULT
 * page size while an over-maximum value appears to be accepted. That is a contract bug that no
 * behavioural test of the endpoint itself can catch, because the endpoint is fine; the description of it
 * is what is wrong.
 *
 * <p><b>This class cannot prove the names WORK, only that they are published.</b> {@code MockMvc} sets
 * parameters directly and never parses a URI, so it stayed green while the real connector was rejecting
 * {@code page[size]} outright — {@code [} is reserved by RFC 3986. {@link CursorPaginationUriTest} is
 * the one that puts the bytes on a socket. Keep both: this one guards the spec, that one guards the wire.
 */
@AutoConfigureMockMvc
class CursorPaginationContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private JsonNode spec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    /** Every operation that takes a cursor page, found by looking for the parameters themselves. */
    private List<String> operationsDeclaring(JsonNode spec, String parameterName) {
        List<String> found = new ArrayList<>();
        spec.path("paths").properties().forEach(path ->
                path.getValue().properties().forEach(verb -> {
                    for (JsonNode parameter : verb.getValue().path("parameters")) {
                        if (parameterName.equals(parameter.path("name").asText())) {
                            found.add(verb.getKey().toUpperCase() + " " + path.getKey());
                        }
                    }
                }));
        return found;
    }

    @Test
    void paginatedOperationsPublishTheBracketedParameterNames() throws Exception {
        JsonNode spec = spec();

        List<String> withSize = operationsDeclaring(spec, "page[size]");
        List<String> withAfter = operationsDeclaring(spec, "page[after]");

        assertThat(withSize).as("at least one paginated operation must exist to make this test meaningful")
                .isNotEmpty();
        assertThat(withAfter).containsExactlyInAnyOrderElementsOf(withSize);
    }

    /**
     * The bug this exists to prevent: a bare {@code page} object parameter, which every generated client
     * turns into names the server ignores.
     */
    @Test
    void noOperationPublishesTheObjectShapedPageParameter() throws Exception {
        JsonNode spec = spec();

        assertThat(operationsDeclaring(spec, "page")).isEmpty();
        assertThat(operationsDeclaring(spec, "size")).isEmpty();
        assertThat(operationsDeclaring(spec, "after")).isEmpty();
        assertThat(spec.path("components").path("schemas").has("CursorPageRequest"))
                .as("the orphaned schema should not linger once the parameters are corrected")
                .isFalse();
    }

    /**
     * {@code X-Impersonate} is read by a filter ahead of the whole chain, so it belongs to no single
     * handler and nothing would naturally document it. An operator reading the spec — or a generated
     * client — has no other way to discover the header exists.
     */
    @Test
    void tenantFacingOperationsDocumentTheImpersonationHeader() throws Exception {
        JsonNode spec = spec();

        assertThat(operationsDeclaring(spec, "X-Impersonate"))
                .contains("GET /api/v1/settings", "GET /api/v1/permissions");
    }

    /**
     * ...but not on {@code /admin/**}: the impersonated principal deliberately holds no platform role,
     * so those operations are 403 for the duration of a session. Advertising the header where it can
     * only ever fail would document a capability that does not exist.
     */
    @Test
    void thePlatformSurfaceDoesNotAdvertiseAHeaderItAlwaysRefuses() throws Exception {
        assertThat(operationsDeclaring(spec(), "X-Impersonate"))
                .noneMatch(operation -> operation.contains("/api/v1/admin/"));
    }

    @Test
    void theDocumentedBoundsAreTheOnesTheResolverEnforces() throws Exception {
        JsonNode parameter = null;
        for (JsonNode path : spec().path("paths")) {
            for (JsonNode verb : path) {
                for (JsonNode candidate : verb.path("parameters")) {
                    if ("page[size]".equals(candidate.path("name").asText())) {
                        parameter = candidate;
                    }
                }
            }
        }
        assertThat(parameter).isNotNull();
        assertThat(parameter.path("schema").path("minimum").asInt()).isEqualTo(1);
        assertThat(parameter.path("schema").path("maximum").asInt()).isEqualTo(CursorPageRequest.MAX_SIZE);
        assertThat(parameter.path("schema").path("default").asInt()).isEqualTo(CursorPageRequest.DEFAULT_SIZE);
    }

    /** And the names actually work end to end — the spec is only useful if the server honours them. */
    @Test
    void theBracketedNamesAreWhatTheServerHonours() throws Exception {
        var admin = jwt().jwt(token -> token.subject("spec-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + PlatformRole.SUPPORT));

        mockMvc.perform(get("/api/v1/admin/users").param("page[size]", "3").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.size").value(3));

        // Over the maximum is a 422, not a silent clamp — the behaviour the flat name hid.
        mockMvc.perform(get("/api/v1/admin/users").param("page[size]", "3919").with(admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("page[size]"));
    }
}
