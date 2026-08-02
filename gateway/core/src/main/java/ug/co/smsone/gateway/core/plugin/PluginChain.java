package ug.co.smsone.gateway.core.plugin;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The continuation a {@link GatewayPlugin} calls to pass control to the next plugin (and ultimately to
 * routing). Passing a mutated exchange to {@link #next} propagates the change downstream; not calling
 * it short-circuits the pipeline.
 */
@FunctionalInterface
public interface PluginChain {

    Mono<Void> next(ServerWebExchange exchange);
}
