package top.focess.veto.integration.plugins;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.service.ServiceRegistration;

/** Packaged test provider using only veto-api contracts. */
public final class InstalledServicePlugin extends AbstractVetoPlugin {
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.installed", "1.0.0");
    }

    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        return new PluginContributions(
                List.of(
                        Contribution.of(
                                StandardContributionPoints.SERVICES,
                                "echo",
                                new ServiceRegistration("sample:echo", 1, request -> request))));
    }

    protected void onStart() {}

    protected void onClose() {}
}
