package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.ServiceDirectoryChangedEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.service.PluginService;
import top.focess.veto.api.plugin.service.ServiceCallContext;

/** Packaged provider that registers its protocol from the initial directory notification. */
public final class InstalledDirectoryPlugin extends VetoPlugin implements Listener {
    private final @NonNull PluginContext context;
    private boolean registered;
    private boolean observed;

    public InstalledDirectoryPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        this.context = context;
        context.register(StandardContributionPoints.LISTENERS, "directory", this);
    }

    @EventHandler
    public void directory(@NonNull ServiceDirectoryChangedEvent event) {
        if (registered) {
            observed = context.services().find("sample:directory", 1).isPresent();
            return;
        }
        registered = true;
        context.register(
                StandardContributionPoints.SERVICES,
                "directory-service",
                new PluginService("sample:directory", 1, PluginScope.APPLICATION) {
                    @Override
                    public @NonNull JsonValue invoke(
                            @NonNull ServiceCallContext caller, @NonNull JsonValue request) {
                        if (request instanceof JsonValue.StringValue value
                                && value.value().equals("register"))
                            context.register(
                                    StandardContributionPoints.SERVICES,
                                    "runtime",
                                    new PluginService(
                                            "sample:runtime", 1, PluginScope.APPLICATION) {
                                        @Override
                                        public @NonNull JsonValue invoke(
                                                @NonNull ServiceCallContext caller,
                                                @NonNull JsonValue request) {
                                            return request;
                                        }
                                    });
                        return new JsonValue.BooleanValue(observed);
                    }
                });
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.directory", "1.0.0");
    }

    @Override
    public void start() {}

    @Override
    public void close() {}
}
