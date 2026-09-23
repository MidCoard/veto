package top.focess.veto.search;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;

/** Bundled search backends, registered through the ordinary plugin API. */
public final class WebSearchPlugin extends AbstractVetoPlugin {
    private final @NonNull DuckDuckGoSearchProvider duckduckgo = new DuckDuckGoSearchProvider();
    private @Nullable BraveSearchProvider brave;

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.web-search", "1.0.100");
    }

    @Override
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        var key = configuration.values().get("brave-api-key");
        var configuredBrave =
                new BraveSearchProvider(
                        key instanceof JsonValue.StringValue value ? value.value() : "");
        brave = configuredBrave;
        return new PluginContributions(
                List.of(
                        Contribution.of(
                                StandardContributionPoints.SEARCH_PROVIDERS,
                                "duckduckgo",
                                duckduckgo),
                        Contribution.of(
                                StandardContributionPoints.SEARCH_PROVIDERS,
                                "brave",
                                configuredBrave)));
    }

    @Override
    protected void onStart() {}

    @Override
    protected void onClose() {
        duckduckgo.close();
        if (brave != null) brave.close();
    }
}
