package ug.co.smsone.gateway.plugin;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-plugin settings — {@code gateway.plugins.<name>.enabled} and {@code .order}. A plugin absent from
 * config runs enabled, at its own {@code defaultOrder()}.
 */
@ConfigurationProperties("gateway")
public record PluginProperties(Map<String, PluginSettings> plugins) {

    public PluginProperties {
        plugins = plugins == null ? Map.of() : plugins;
    }

    public boolean enabled(String name) {
        PluginSettings settings = plugins.get(name);
        return settings == null || settings.enabled() == null || settings.enabled();
    }

    public int order(String name, int defaultOrder) {
        PluginSettings settings = plugins.get(name);
        return settings == null || settings.order() == null ? defaultOrder : settings.order();
    }

    public record PluginSettings(Boolean enabled, Integer order) {
    }
}
