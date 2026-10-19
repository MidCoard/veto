package top.focess.veto.builtin.tools;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.builtin.BundledFrontend;

/** Builtin presentation ships with the plugin and requires no backend actions. */
public final class ToolsFrontend extends BundledFrontend {
    /** Constructs the builtin tool presentation aspect. */
    public ToolsFrontend() {
        super("/frontend/tools.js");
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
