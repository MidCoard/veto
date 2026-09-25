package top.focess.veto.builtin.search;

import java.net.http.HttpTimeoutException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.search.SearchOptions;
import top.focess.veto.api.search.SearchProvider;
import top.focess.veto.api.search.SearchResult;
import top.focess.veto.api.search.SearchServices;

/** Plugin-owned selection; the host directory admits the caller and provider for each session. */
public final class SearchServiceClient implements SearchProvider {
    private final @NonNull PluginContext context;
    private final @NonNull String selected;

    /** Reads the {@code search-provider} selection from configuration, defaulting to duckduckgo. */
    public SearchServiceClient(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        this.context = context;
        var value = configuration.values().get("search-provider");
        selected = value instanceof JsonValue.StringValue name ? name.value() : "duckduckgo";
    }

    public @NonNull String name() {
        return selected;
    }

    public @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception {
        context.service(ToolDocs.nonNullClass(PluginHost.class))
                .orElseThrow(() -> new SecurityException("Host must authorize search invocation"))
                .invocation("web_search");
        var handle =
                context.services()
                        .find(SearchServices.name(selected), 1)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Selected search provider is unavailable"));
        try {
            return SearchServices.results(handle.invoke(SearchServices.request(query, options)));
        } catch (ServiceException failure) {
            if (failure.code() == ServiceException.Code.TIMEOUT)
                throw new HttpTimeoutException("Search provider timed out");
            throw new IllegalStateException("Selected search provider failed");
        }
    }
}
