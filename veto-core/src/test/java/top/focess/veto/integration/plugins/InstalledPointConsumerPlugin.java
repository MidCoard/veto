package top.focess.veto.integration.plugins;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contribution.ContributionId;
import top.focess.veto.api.plugin.contribution.ContributionPoint;

/** Registers through a point declared by a later-initialized package. */
public final class InstalledPointConsumerPlugin extends VetoPlugin {
    private final @NonNull PluginContext context;

    public InstalledPointConsumerPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        this.context = context;
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.consumer", "1.0.0");
    }

    @Override
    public void start() {
        context.register(
                new ContributionPoint<>(
                        new ContributionId("sample.point:shared"),
                        1,
                        JsonValue.ObjectValue.class,
                        ContributionPoint.Cardinality.MULTIPLE),
                "sample",
                new JsonValue.ObjectValue(
                        Map.of("value", new JsonValue.StringValue("from-consumer"))));
    }

    @Override
    public void close() {}
}
