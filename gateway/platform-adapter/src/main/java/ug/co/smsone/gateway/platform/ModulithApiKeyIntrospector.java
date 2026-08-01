package ug.co.smsone.gateway.platform;

import java.util.Map;
import java.util.Set;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.security.ApiKeyIntrospector;
import ug.co.smsone.gateway.core.security.EdgePrincipal;

/**
 * The first platform adapter: resolves an API key by calling the platform's introspection endpoint
 * over reactive HTTP, presenting the shared gateway secret. The platform authenticates the key with
 * its own store (the {@code ApiKeyAuthenticator} port) and answers RFC 7662-style — {@code active}
 * plus the subject/tenant/scopes. An inactive key yields an EMPTY result (→ 401 at the edge); a
 * failed CALL propagates (→ 502) rather than silently admitting the request.
 */
class ModulithApiKeyIntrospector implements ApiKeyIntrospector {

    private final WebClient webClient;
    private final String secret;

    ModulithApiKeyIntrospector(IntrospectionProperties properties) {
        this.webClient = WebClient.create(properties.uri());
        this.secret = properties.secret();
    }

    @Override
    public Mono<EdgePrincipal> introspect(String apiKey) {
        return webClient.post()
                .header("X-Gateway-Secret", secret)
                .bodyValue(Map.of("apiKey", apiKey))
                .retrieve()
                .bodyToMono(IntrospectionResult.class)
                .filter(IntrospectionResult::active)
                .map(result -> new EdgePrincipal(result.subject(), result.tenant(),
                        result.scopes() == null ? Set.of() : result.scopes()));
    }

    record IntrospectionResult(boolean active, String subject, String tenant, Set<String> scopes) {
    }
}
