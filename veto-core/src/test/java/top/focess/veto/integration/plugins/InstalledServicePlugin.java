package top.focess.veto.integration.plugins;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.service.PluginService;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.service.ServiceScope;

/** Packaged test provider using only veto-api contracts. */
public final class InstalledServicePlugin extends VetoPlugin {
    @Override
    public @NonNull PluginContributions contributions() {
        return new PluginContributions(
                List.of(
                        Contribution.of(
                                StandardContributionPoints.SERVICES,
                                "echo",
                                new PluginService("sample:echo", 1, ServiceScope.GLOBAL) {
                                    @Override
                                    public @NonNull JsonValue invoke(
                                            @NonNull ServiceCallContext caller,
                                            @NonNull JsonValue request) {
                                        return request;
                                    }
                                })));
    }

    public InstalledServicePlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {}

    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.installed", "1.0.0");
    }

    public void start() {}

    public void close() {}
}
