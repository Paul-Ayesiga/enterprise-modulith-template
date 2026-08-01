package ug.co.smsone.gateway.core.security;

import reactor.core.publisher.Mono;

/**
 * Port — resolve a presented API key to an {@link EdgePrincipal}. The platform supplies the
 * implementation (an adapter that calls its key store); the core knows only the contract. An EMPTY
 * result means the key is not active/valid (→ the caller is unauthenticated). When no adapter is on
 * the context, API-key authentication is simply unavailable — the bearer path still works.
 */
public interface ApiKeyIntrospector {

    Mono<EdgePrincipal> introspect(String apiKey);
}
