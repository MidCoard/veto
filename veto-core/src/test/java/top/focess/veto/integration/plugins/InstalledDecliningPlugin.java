package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginDeclinedException;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Installed fixture that intentionally declines before publishing contributions. */
public final class InstalledDecliningPlugin extends VetoPlugin {
    @Override
    public @NonNull PluginContributions contributions() {
        throw new PluginDeclinedException(PluginDeclinedException.Reason.UNSUPPORTED_ENVIRONMENT);
    }

    public InstalledDecliningPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {}

    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.declined", "1.0.0");
    }

    public void start() {
        throw new AssertionError("A declined plugin must not start");
    }

    public void close() {}
}
