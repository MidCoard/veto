package top.focess.veto.plugin.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;

/** Portable bytecode packaged by InstalledPluginLoaderTest, without runtime-module dependencies. */
public final class InstalledSamplePlugin extends VetoPlugin {

    static {
        String previous = System.setProperty("veto.test.installed-sample.initialized", "true");
        if (previous != null)
            System.getLogger("veto.test.installed-sample")
                    .log(System.Logger.Level.DEBUG, "Previous initialization marker: " + previous);
    }

    public InstalledSamplePlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        context.service(AtomicInteger.class)
                .ifPresent(
                        closed -> {
                            context.register(
                                    StandardContributionPoints.RESOURCES,
                                    "partial",
                                    closed::incrementAndGet);
                            throw new IllegalStateException(
                                    "Sample construction failed after resource registration");
                        });
    }

    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.install", "1.0.0");
    }

    public void start() {}

    public void close() {}
}
