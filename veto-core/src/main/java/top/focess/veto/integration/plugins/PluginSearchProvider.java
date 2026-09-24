package top.focess.veto.integration.plugins;

import java.net.http.HttpTimeoutException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.search.SearchOptions;
import top.focess.veto.api.search.SearchProvider;
import top.focess.veto.api.search.SearchResult;
import top.focess.veto.api.search.SearchServices;
import top.focess.veto.plugin.runtime.*;

/** Authorized host adapter to the ordinary named service directory. */
@Component
public final class PluginSearchProvider implements SearchProvider {
    private final @NonNull PluginManager manager;
    private final @NonNull SessionPlugins sessions;
    private final @NonNull String selected;

    public PluginSearchProvider(
            @NonNull PluginManager manager,
            @NonNull SessionPlugins sessions,
            @Value("${veto.websearch.provider:duckduckgo}") @NonNull String selected) {
        this.manager = manager;
        this.sessions = sessions;
        this.selected = selected;
    }

    public @NonNull String name() {
        return selected;
    }

    public @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception {
        var context = CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, "web_search");
        var handle =
                manager.services()
                        .find(SearchServices.name(selected), 1)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Selected search provider is unavailable"));
        var sessionId = context.sessionId();
        if (sessionId == null
                || !sessions.includes(sessionId.toString(), handle.descriptor().providerId()))
            throw new IllegalStateException(
                    "Selected search provider is unavailable for this session");
        try {
            return SearchServices.results(handle.invoke(SearchServices.request(query, options)));
        } catch (ServiceException failure) {
            if (failure.code() == ServiceException.Code.TIMEOUT)
                throw new HttpTimeoutException("Search provider timed out");
            throw new IllegalStateException("Selected search provider failed");
        }
    }
}
