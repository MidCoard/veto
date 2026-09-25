package top.focess.veto.distribution;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.focess.veto.integration.plugins.PluginConfigurations;

/** Maps the old distribution setting after binding; explicit builtin settings win. */
@Component
public final class LegacyPlanConfiguration implements BeanPostProcessor, Ordered {
    private final @NonNull Environment environment;

    /** Creates the bridge reading the legacy plan property from the given environment. */
    public LegacyPlanConfiguration(@NonNull Environment environment) {
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
        String legacy = environment.getProperty("veto.plan.max-steps");
        if (legacy == null) return bean;
        Map<@NonNull String, @NonNull String> values = new LinkedHashMap<>();
        values.put("plan-max-steps", legacy);
        values.putAll(
                configuration.getConfiguration().getOrDefault("top.focess.builtin", Map.of()));
        var merged = new LinkedHashMap<>(configuration.getConfiguration());
        merged.put("top.focess.builtin", Map.copyOf(values));
        configuration.setConfiguration(merged);
        return bean;
    }
}
