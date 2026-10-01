package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.service.PluginService;
import top.focess.veto.api.plugin.service.ServiceCallContext;

/** Packaged test provider using only veto-api contracts. */
public final class InstalledServicePlugin extends VetoPlugin {
    public InstalledServicePlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        context.register(
                StandardContributionPoints.SERVICES,
                "echo",
                new PluginService("sample:echo", 1, PluginScope.APPLICATION) {
                    @Override
                    public @NonNull JsonValue invoke(
                            @NonNull ServiceCallContext caller, @NonNull JsonValue request) {
                        if (request instanceof JsonValue.StringValue value
                                && value.value().equals("register"))
                            context.register(
                                    StandardContributionPoints.SERVICES,
                                    "late",
                                    new PluginService("sample:late", 1, PluginScope.APPLICATION) {
                                        @Override
                                        public @NonNull JsonValue invoke(
                                                @NonNull ServiceCallContext caller,
                                                @NonNull JsonValue request) {
                                            return request;
                                        }
                                    });
                        return request;
                    }
                });
    }

    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.installed", "1.0.0");
    }

    public void start() {}

    public void close() {}
}
