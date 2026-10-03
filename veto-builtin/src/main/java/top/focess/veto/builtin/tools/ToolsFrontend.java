package top.focess.veto.builtin.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Builtin presentation ships with the plugin and requires no backend actions. */
public final class ToolsFrontend implements FrontendContribution {
    /** Constructs the builtin tool presentation aspect. */
    public ToolsFrontend() {}

    /** Serves the bundled tools script; every frontend action is rejected. */
    @Override
    public @NonNull String module() {
        try (var stream = ToolsFrontend.class.getResourceAsStream("/frontend/tools.js")) {
            if (stream == null) throw new IllegalStateException("Missing tools frontend");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load tools frontend", failure);
        }
    }

    @Override
    public @NonNull JsonValue handle(
            Scope.@NonNull AgentScope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue arguments)
            throws PluginFailure {
        throw new IllegalArgumentException("Tools presentation has no actions");
    }
}
