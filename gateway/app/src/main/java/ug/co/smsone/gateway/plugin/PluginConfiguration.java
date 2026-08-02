package ug.co.smsone.gateway.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ug.co.smsone.gateway.core.plugin.GatewayPlugin;

/**
 * Wires the plugin framework: discovers every {@link GatewayPlugin} bean on the context and hands them
 * to the {@link PluginGlobalFilter}, which decides (from config) which run and in what order.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PluginProperties.class)
class PluginConfiguration {

    @Bean
    PluginGlobalFilter pluginGlobalFilter(ObjectProvider<GatewayPlugin> plugins, PluginProperties properties) {
        return new PluginGlobalFilter(plugins.stream().toList(), properties);
    }
}
