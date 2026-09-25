package top.focess.veto.distribution;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.focess.veto.integration.plugins.PluginConfigurations;

/** Distribution upgrade bridge; reader defaults and execution policy belong to builtin. */
@Component
public final class LegacyReaderConfiguration implements BeanPostProcessor, Ordered {
    private final @NonNull Environment environment;

    /** Creates the bridge reading the legacy webfetch properties from the given environment. */
    public LegacyReaderConfiguration(@NonNull Environment environment) {
        this.environment = environment;
    }

    /** Runs after ConfigurationProperties binding and before PluginManager reads the bean. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public @NonNull Object postProcessBeforeInitialization(
            @NonNull Object bean, @NonNull String name) {
        if (!(bean instanceof PluginConfigurations configuration)) return bean;
        Map<@NonNull String, @NonNull String> values = new LinkedHashMap<>();
        for (String key :
                new String[] {
                    "model-tier",
                    "max-rounds",
                    "timeout-seconds",
                    "max-input-tokens",
                    "max-output-tokens"
                }) {
            String value = environment.getProperty("veto.webfetch." + key);
            if (value != null) values.put("reader-" + key, value);
        }
        if (values.isEmpty()) return bean;
        values.putAll(
                configuration.getConfiguration().getOrDefault("top.focess.builtin", Map.of()));
        var merged = new LinkedHashMap<>(configuration.getConfiguration());
        merged.put("top.focess.builtin", Map.copyOf(values));
        configuration.setConfiguration(merged);
        return bean;
    }
}
