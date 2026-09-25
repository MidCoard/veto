package example;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.service.ServiceRegistration;

/** Compilation fixture for the provider documented in veto-api/README.md. */
public final class TextProviderPlugin implements VetoPlugin {
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("example.text", "1.0.0");
    }

    public @NonNull PluginContributions initialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        var service =
                new ServiceRegistration(
                        "example:text",
                        1,
                        request ->
                                request instanceof JsonValue.StringValue text
                                        ? new JsonValue.StringValue(text.value().trim())
                                        : JsonValue.NullValue.INSTANCE);
        return new PluginContributions(
                List.of(Contribution.of(StandardContributionPoints.SERVICES, "text", service)));
    }

    public void start() throws PluginFailure {}

    public void close() throws PluginFailure {}
}
