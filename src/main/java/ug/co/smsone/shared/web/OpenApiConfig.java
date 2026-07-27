package ug.co.smsone.shared.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 definition. Two auth schemes so Postman (which imports this spec natively) can either
 * paste a bearer token or run the Keycloak authorization-code flow directly.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";
    public static final String OAUTH2_SCHEME = "keycloak";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Bean
    OpenAPI apiDefinition(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${OPENAPI_LOCAL_URL:http://localhost:8080}") String localUrl,
            @Value("${OPENAPI_STAGING_URL:https://staging-api.smsone.co.ug}") String stagingUrl,
            @Value("${OPENAPI_PROD_URL:https://api.smsone.co.ug}") String prodUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("SMSOne Enterprise API")
                        .version(ApiMetaFactory.API_VERSION)
                        .description("""
                                Enterprise Spring Modulith template API. Every response uses the \
                                unified envelope ({data | errors, meta, links}) with meta.requestId \
                                always present; quote the requestId when reporting issues.""")
                        .contact(new Contact().name("SMSOne").email("david@smsone.co.ug")))
                .servers(List.of(
                        new Server().url(localUrl).description("Local"),
                        new Server().url(stagingUrl).description("Staging"),
                        new Server().url(prodUrl).description("Production")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste a Keycloak-issued access token."))
                        .addSecuritySchemes(OAUTH2_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("Run the Keycloak authorization-code flow (PKCE).")
                                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                        .authorizationUrl(issuerUri + "/protocol/openid-connect/auth")
                                        .tokenUrl(issuerUri + "/protocol/openid-connect/token")
                                        .scopes(new Scopes()
                                                .addString("openid", "OpenID Connect")
                                                .addString("profile", "Profile claims"))))))
                .security(List.of(
                        new SecurityRequirement().addList(BEARER_SCHEME),
                        new SecurityRequirement().addList(OAUTH2_SCHEME)));
    }

    /** Documents the always-present X-Request-Id response header on every operation. */
    @Bean
    OperationCustomizer requestIdHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            if (operation.getResponses() != null) {
                operation.getResponses().forEach((status, response) -> response.addHeaderObject(
                        REQUEST_ID_HEADER,
                        new Header()
                                .description("Public request id (accepted inbound, else minted as a ULID).")
                                .schema(new StringSchema())));
            }
            return operation;
        };
    }
}
