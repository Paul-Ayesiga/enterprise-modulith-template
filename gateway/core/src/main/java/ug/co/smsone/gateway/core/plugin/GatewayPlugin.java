package ug.co.smsone.gateway.core.plugin;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Port — a unit of custom edge behavior that runs in the request pipeline. The platform (or an
 * operator) adds a {@code GatewayPlugin} bean and it is discovered, ordered, and run automatically;
 * config toggles it on/off and reorders it without code ({@code gateway.plugins.<name>.enabled/order}).
 * A plugin wraps the rest of the chain: it may inspect/mutate the exchange, short-circuit (skip {@code
 * chain.next}), or post-process by composing onto the returned {@link Mono}. It depends on the edge
 * abstractions, not the runtime — the same plugin could run on a different gateway implementation.
 *
 * <p>Plugins run as one pipeline stage (after edge authentication, before routing), in the order given
 * by {@link #defaultOrder()} unless overridden in config.
 */
public interface GatewayPlugin {

    /** Stable name — the key used to enable/disable and reorder this plugin in config. */
    String name();

    /** Run this plugin; call {@code chain.next(exchange)} to continue, or return without it to stop. */
    Mono<Void> filter(ServerWebExchange exchange, PluginChain chain);

    /** Default position among plugins (lower runs first); overridable via {@code gateway.plugins.<name>.order}. */
    default int defaultOrder() {
        return 0;
    }
}
