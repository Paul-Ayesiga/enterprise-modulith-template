package ug.co.smsone.gateway.quota;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.quota.ConsumerResolver;
import ug.co.smsone.gateway.core.security.EdgePrincipal;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * The default consumer resolver: meter a request against its authenticated principal's tenant — the
 * organization whose subscription carries the quota. An unauthenticated or untenanted request has no
 * consumer, so no quota applies. Replace this bean to key quotas differently (per API-key consumer, per
 * Keycloak client, …).
 */
@Component
class PrincipalConsumerResolver implements ConsumerResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        EdgePrincipal principal = GatewayAttributes.principal(exchange);
        if (principal != null && principal.tenant() != null && !principal.tenant().isBlank()) {
            return Mono.just(principal.tenant());
        }
        return Mono.empty();
    }
}
