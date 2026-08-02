package ug.co.smsone.gateway.core.quota;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Port — resolve the consumer a request should be metered against (the entity whose plan carries the
 * quota: an organization/tenant, an API-key consumer, a Keycloak client). An EMPTY result means no
 * consumer could be resolved, so no quota applies. The platform supplies the resolution; the core knows
 * only the contract.
 */
public interface ConsumerResolver {

    Mono<String> resolve(ServerWebExchange exchange);
}
