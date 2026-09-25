package top.focess.veto.distribution;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.focess.veto.integration.plugins.PluginConfigurations;

/**
 * Distribution-only aliases; builtin owns defaults and explicit plugin settings take precedence.
 */
@Component
public final class LegacySearchConfiguration implements BeanPostProcessor, Ordered {
    private final @NonNull Environment environment;

    /** Creates the bridge reading the legacy websearch properties from the given environment. */
    public LegacySearchConfiguration(@NonNull Environment environment) {
        this.environment = environment;
    }

    /** Runs last, after property binding and before consumers read the configuration bean. */
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Maps legacy search provider and API key onto builtin configuration, which takes precedence.
     */
    public @NonNull Object postProcessBeforeInitialization(
            @NonNull Object bean, @NonNull String name) {
        if (!(bean instanceof PluginConfigurations configurations)) return bean;
        Map<String, String> values = new LinkedHashMap<>();
        String selected = environment.getProperty("veto.websearch.provider");
        String key = environment.getProperty("veto.websearch.brave.api-key");
        if (key == null) key = environment.getProperty("BRAVE_API_KEY");
        if (selected != null) values.put("search-provider", selected);
        if (key != null) values.put("brave-api-key", key);
        if (values.isEmpty()) return bean;
        values.putAll(
                configurations.getConfiguration().getOrDefault("top.focess.builtin", Map.of()));
        var merged = new LinkedHashMap<>(configurations.getConfiguration());
        merged.put("top.focess.builtin", Map.copyOf(values));
        configurations.setConfiguration(merged);
        return bean;
    }
}
