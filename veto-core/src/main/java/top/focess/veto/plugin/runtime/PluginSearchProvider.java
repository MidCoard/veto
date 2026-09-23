package top.focess.veto.plugin.runtime;

import java.net.http.HttpTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionEntry;
import top.focess.veto.api.search.SearchOptions;
import top.focess.veto.api.search.SearchProvider;
import top.focess.veto.api.search.SearchResult;

/** Resolves the configured backend without importing any provider implementation. */
@Component
public final class PluginSearchProvider implements SearchProvider {
    private final @NonNull PluginManager manager;
    private final @NonNull SessionPlugins sessions;
    private final @NonNull String selected;
    private final @NonNull Map<String, ContributionEntry<SearchProvider>> providers;

    public PluginSearchProvider(
            @NonNull PluginManager manager,
            @NonNull SessionPlugins sessions,
            @Value("${veto.websearch.provider:duckduckgo}") @NonNull String selected) {
        this.manager = manager;
        this.sessions = sessions;
        this.selected = selected;
        Map<String, ContributionEntry<SearchProvider>> entries = new HashMap<>();
        for (var entry : manager.catalog().entries(StandardContributionPoints.SEARCH_PROVIDERS)) {
            String name = entry.implementation().name();
            if (name.isBlank() || entries.putIfAbsent(name, entry) != null)
                throw new IllegalArgumentException("Duplicate or empty search provider name");
        }
        providers = Map.copyOf(entries);
    }

    @Override
    public @NonNull String name() {
        return selected;
    }

    @Override
    public @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception {
        var context = CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, "web_search");
        var entry = providers.get(selected);
        var sessionId = context.sessionId();
        if (entry == null
                || sessionId == null
                || !sessions.includes(sessionId.toString(), entry.source().namespace()))
            throw new IllegalStateException(
                    "Selected search provider is unavailable for this session");
        Outcome outcome;
        try {
            outcome =
                    manager.plugin(entry.source().namespace())
                            .execute(
                                    () -> {
                                        try {
                                            return new Outcome(
                                                    List.copyOf(
                                                            entry.implementation()
                                                                    .search(query, options)),
                                                    null);
                                        } catch (Exception failure) {
                                            return new Outcome(List.of(), failure);
                                        }
                                    });
        } catch (PluginFailure failure) {
            throw new IllegalStateException("Selected search provider is unavailable");
        }
        if (outcome.failure() instanceof HttpTimeoutException)
            throw new HttpTimeoutException("Search provider timed out");
        if (outcome.failure() != null) throw new IllegalStateException("Search provider failed");
        return outcome.results();
    }

    private record Outcome(@NonNull List<SearchResult> results, @Nullable Exception failure) {}
}
