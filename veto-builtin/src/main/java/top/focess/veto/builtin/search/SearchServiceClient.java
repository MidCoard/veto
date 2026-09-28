package top.focess.veto.builtin.search;

import java.net.http.HttpTimeoutException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.service.ServiceException;

/** Plugin-owned selection; the host directory admits the caller and provider for each session. */
public final class SearchServiceClient implements SearchProvider {
    private final @NonNull PluginContext context;
    private final @NonNull String selected;

    /** Reads the {@code search-provider} selection from configuration, defaulting to duckduckgo. */
    public SearchServiceClient(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        this.context = context;
        var value = configuration.values().get("search-provider");
        selected =
                value instanceof JsonValue.StringValue
                        ? ((JsonValue.StringValue) value).value()
                        : "duckduckgo";
    }

    public @NonNull String name() {
        return selected;
    }

    public @NonNull List<@NonNull SearchResult> search(
            @NonNull String query, @NonNull SearchOptions options) throws Exception {
        context.service(PluginHost.class)
                .orElseThrow(() -> new SecurityException("Host must authorize search invocation"))
                .invocation("web_search");
        var handle =
                context.services()
                        .find(SearchProtocol.NAME, 1)
                        .orElseThrow(
                                () -> new IllegalStateException("Search service is unavailable"));
        try {
            return SearchProtocol.results(
                    handle.invoke(SearchProtocol.searchRequest(selected, query, options)));
        } catch (ServiceException failure) {
            if (failure.code() == ServiceException.Code.TIMEOUT)
                throw new HttpTimeoutException("Search provider timed out");
            throw new IllegalStateException("Selected search provider failed");
        }
    }
}
