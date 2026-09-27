package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginDeclinedException;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Installed fixture that intentionally declines before publishing contributions. */
public final class InstalledDecliningPlugin extends AbstractVetoPlugin {
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.declined", "1.0.0");
    }

    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        throw new PluginDeclinedException(PluginDeclinedException.Reason.UNSUPPORTED_ENVIRONMENT);
    }

    protected void onStart() {
        throw new AssertionError("A declined plugin must not start");
    }

    protected void onClose() {}
}
