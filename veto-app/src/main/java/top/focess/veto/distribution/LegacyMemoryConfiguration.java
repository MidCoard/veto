package top.focess.veto.distribution;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.focess.veto.integration.plugins.PluginConfigurations;

/** Keeps legacy store selection; explicit builtin configuration wins. */
@Component
public final class LegacyMemoryConfiguration implements BeanPostProcessor, Ordered {
    private final @NonNull Environment environment;

    /** Creates the bridge reading the legacy memory store property from the given environment. */
    public LegacyMemoryConfiguration(@NonNull Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public @NonNull Object postProcessBeforeInitialization(
            @NonNull Object bean, @NonNull String name) {
        if (!(bean instanceof PluginConfigurations configuration)) return bean;
        String profile = environment.getProperty("veto.memory.store");
        if (profile == null) return bean;
        Map<@NonNull String, @NonNull String> values = new LinkedHashMap<>();
        values.put("memory-store", profile);
        values.putAll(
                configuration.getConfiguration().getOrDefault("top.focess.builtin", Map.of()));
        var merged = new LinkedHashMap<>(configuration.getConfiguration());
        merged.put("top.focess.builtin", Map.copyOf(values));
        configuration.setConfiguration(merged);
        return bean;
    }
}
