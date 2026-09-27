package top.focess.veto.plugin.runtime;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Portable bytecode packaged by InstalledPluginLoaderTest, without runtime-module dependencies. */
public final class InstalledSamplePlugin extends AbstractVetoPlugin {
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.install", "1.0.0");
    }

    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        return new PluginContributions(List.of());
    }

    protected void onStart() {}

    protected void onClose() {}
}
