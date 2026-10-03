package top.focess.veto.integration.plugins;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionId;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.contribution.ProtocolPointDefinition;

/** Defines a point that another packaged plugin contributes to during start. */
public final class InstalledPointPlugin extends VetoPlugin {
    public InstalledPointPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        context.register(
                StandardContributionPoints.CONTRIBUTIONS,
                "shared",
                new ProtocolPointDefinition(
                        new ContributionId("sample.point:shared"),
                        1,
                        new JsonValue.ObjectValue(
                                Map.of(
                                        "type",
                                        new JsonValue.StringValue("object"),
                                        "properties",
                                        new JsonValue.ObjectValue(
                                                Map.of(
                                                        "value",
                                                        new JsonValue.ObjectValue(
                                                                Map.of(
                                                                        "type",
                                                                        new JsonValue.StringValue(
                                                                                "string"))))),
                                        "additionalProperties",
                                        new JsonValue.BooleanValue(false))),
                        ContributionPoint.Cardinality.MULTIPLE));
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("sample.point", "1.0.0");
    }

    @Override
    public void start() {}

    @Override
    public void close() {}
}
