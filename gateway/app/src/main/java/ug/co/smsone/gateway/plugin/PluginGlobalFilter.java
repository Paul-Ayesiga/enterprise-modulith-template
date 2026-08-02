package ug.co.smsone.gateway.plugin;

import java.util.Comparator;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.plugin.GatewayPlugin;
import ug.co.smsone.gateway.core.plugin.PluginChain;

/**
 * Runs the registered {@link GatewayPlugin}s as one pipeline stage — after edge authentication (so a
 * plugin sees the resolved principal) and before routing. Enabled plugins (per {@link PluginProperties})
 * are ordered and folded into a chain whose innermost continuation is SCG's own filter chain, so a
 * plugin can inspect/mutate the exchange, short-circuit, or post-process. Adding a plugin bean extends
 * the edge with no change here; config toggles and reorders it. This adapter is the only place a plugin
 * meets the SCG runtime — plugins themselves know only the core abstractions.
 */
class PluginGlobalFilter implements GlobalFilter, Ordered {

    private final List<GatewayPlugin> plugins;

    PluginGlobalFilter(List<GatewayPlugin> allPlugins, PluginProperties properties) {
        this.plugins = allPlugins.stream()
                .filter(plugin -> properties.enabled(plugin.name()))
                .sorted(Comparator.comparingInt(plugin -> properties.order(plugin.name(), plugin.defaultOrder())))
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        PluginChain pluginChain = chain::filter; // innermost continuation = the SCG chain (→ routing)
        for (int i = plugins.size() - 1; i >= 0; i--) {
            GatewayPlugin plugin = plugins.get(i);
            PluginChain next = pluginChain;
            pluginChain = exchangeAtStage -> plugin.filter(exchangeAtStage, next);
        }
        return pluginChain.next(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20; // after request-id/trace/access-log and auth (+10), before routing
    }
}
