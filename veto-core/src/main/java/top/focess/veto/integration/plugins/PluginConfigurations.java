package top.focess.veto.integration.plugins;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.plugin.runtime.*;

/** Operator configuration passed only to the plugin with the matching identity. */
@Component
@ConfigurationProperties(prefix = "veto.plugins")
public final class PluginConfigurations {
    private @NonNull Map<@NonNull String, @NonNull String> toolNames = Map.of();

    public @NonNull Map<@NonNull String, @NonNull String> getToolNames() {
        return toolNames;
    }

    public void setToolNames(@NonNull Map<@NonNull String, @NonNull String> toolNames) {
        this.toolNames = Map.copyOf(toolNames);
    }

    private @NonNull Map<@NonNull String, @NonNull Map<@NonNull String, @NonNull String>>
            configuration = Map.of();

    public @NonNull Map<@NonNull String, @NonNull Map<@NonNull String, @NonNull String>>
            getConfiguration() {
        return configuration;
    }

    public void setConfiguration(
            @NonNull Map<@NonNull String, @NonNull Map<@NonNull String, @NonNull String>>
                    configuration) {
        this.configuration = configuration;
    }

    public JsonValue.@NonNull ObjectValue forPlugin(@NonNull String id) {
        Map<@NonNull String, @NonNull JsonValue> values = new LinkedHashMap<>();
        configuration
                .getOrDefault(id, Map.of())
                .forEach((key, value) -> values.put(key, new JsonValue.StringValue(value)));
        return new JsonValue.ObjectValue(values);
    }
}
