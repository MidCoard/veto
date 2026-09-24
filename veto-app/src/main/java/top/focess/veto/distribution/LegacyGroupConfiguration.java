package top.focess.veto.distribution;

import java.util.LinkedHashMap;
import java.util.Map;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.focess.veto.integration.plugins.PluginConfigurations;

/** Distribution upgrade aliases; explicit plugin configuration always wins. */
@Component
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class LegacyGroupConfiguration implements BeanPostProcessor, Ordered {
    private final Environment environment;

    public LegacyGroupConfiguration(Environment environment) {
        this.environment = environment;
    }

    /** Property binding is PriorityOrdered; bridge runs afterwards, before bean consumers. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String name) {
        if (!(bean instanceof PluginConfigurations configurations)) return bean;
        Map<String, String> values = new LinkedHashMap<>();
        copy(values, "tick.interval-ms", "group-tick-interval-ms");
        copy(values, "leader.tier", "group-leader-tier");
        copy(values, "mate.tier", "group-mate-tier");
        copy(values, "leader.system-prompt-base", "group-leader-guidance");
        copy(values, "mate.system-prompt-base", "group-mate-guidance");
        var skillsets =
                Binder.get(environment)
                        .bind("veto.group.skillsets", Bindable.mapOf(String.class, Skillset.class))
                        .orElse(Map.of());
        skillsets.forEach(
                (id, skillset) -> {
                    String tier = skillset.getTier();
                    String guidance = skillset.getSystemPromptBase();
                    if (tier != null) values.put("group-skillset." + id + ".tier", tier);
                    if (guidance != null)
                        values.put("group-skillset." + id + ".guidance", guidance);
                });
        values.putAll(
                configurations.getConfiguration().getOrDefault("top.focess.builtin", Map.of()));
        var merged = new LinkedHashMap<>(configurations.getConfiguration());
        merged.put("top.focess.builtin", Map.copyOf(values));
        configurations.setConfiguration(merged);
        return bean;
    }

    private void copy(Map<String, String> values, String oldKey, String newKey) {
        String value = environment.getProperty("veto.group." + oldKey);
        if (value != null) values.put(newKey, value);
    }

    public static final class Skillset {
        private @Nullable String tier;
        private @Nullable String systemPromptBase;

        public @Nullable String getTier() {
            return tier;
        }

        public void setTier(String value) {
            tier = value;
        }

        public @Nullable String getSystemPromptBase() {
            return systemPromptBase;
        }

        public void setSystemPromptBase(String value) {
            systemPromptBase = value;
        }
    }
}
