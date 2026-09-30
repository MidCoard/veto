package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.plugin.runtime.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Operator configuration passed only to the plugin with the matching identity. */
@Component
@ConfigurationProperties(prefix = "veto.plugins")
public final class PluginConfigurations {
    private @NonNull Set<String> disabled = Set.of();

    /** Startup defaults, overridden by administrator choices saved in the database. */
    public @NonNull Set<String> getDisabled() {
        return disabled;
    }

    /** Configures default inactive IDs when no database choice exists. */
    public void setDisabled(@NonNull Set<String> disabled) {
        this.disabled = Set.copyOf(disabled);
    }

    private @NonNull Map<String, Map<String, String>> catalogueRoots = Map.of();

    public @NonNull Map<String, Map<String, String>> getCatalogueRoots() {
        return catalogueRoots;
    }

    public void setCatalogueRoots(@NonNull Map<String, Map<String, String>> roots) {
        catalogueRoots = Map.copyOf(roots);
    }

    private @NonNull ScriptExecutionMode scriptMode = ScriptExecutionMode.TRUSTED;

    public @NonNull ScriptExecutionMode getScriptMode() {
        return scriptMode;
    }

    public void setScriptMode(@NonNull ScriptExecutionMode scriptMode) {
        this.scriptMode = scriptMode;
    }

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

    /** Operator-configured values for the given plugin id as JSON; empty when none are set. */
    public JsonValue.@NonNull ObjectValue forPlugin(@NonNull String id) {
        Map<@NonNull String, @NonNull JsonValue> values = new LinkedHashMap<>();
        configuration
                .getOrDefault(id, Map.of())
                .forEach((key, value) -> values.put(key, new JsonValue.StringValue(value)));
        return new JsonValue.ObjectValue(values);
    }
}
