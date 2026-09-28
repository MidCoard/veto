package top.focess.veto.plugin.runtime;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Portable bytecode packaged by InstalledPluginLoaderTest, without runtime-module dependencies. */
public final class InstalledSamplePlugin extends VetoPlugin {
    @Override
    public @NonNull PluginContributions contributions() {
        return new PluginContributions(List.of());
    }

    public InstalledSamplePlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {}

    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.install", "1.0.0");
    }

    public void start() {}

    public void close() {}
}
