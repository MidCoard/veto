package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;

/** Immutable descriptor; handler names are package-owned, never supplied by model input. */
public record ScriptTool(
        @NonNull String id,
        @NonNull String description,
        @NonNull String handler,
        @NonNull JsonNode inputSchema,
        @NonNull JsonNode outputSchema) {
    public ScriptTool {
        PluginSchema.require(id.matches("[a-z][a-z0-9_]{0,31}"));
        PluginSchema.require(handler.matches("[a-zA-Z][a-zA-Z0-9_.]{0,95}"));
        PluginSchema.require(!description.isBlank() && description.length() <= 4096);
        PluginSchema.check(inputSchema);
        PluginSchema.require(inputSchema.path("type").asText().equals("object"));
        PluginSchema.check(outputSchema);
        inputSchema = inputSchema.deepCopy();
        outputSchema = outputSchema.deepCopy();
    }

    @Override
    public @NonNull JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public @NonNull JsonNode outputSchema() {
        return outputSchema.deepCopy();
    }
}
