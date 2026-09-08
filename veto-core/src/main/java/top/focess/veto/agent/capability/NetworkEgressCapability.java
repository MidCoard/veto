package top.focess.veto.agent.capability;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.web.SearchOptions;
import top.focess.veto.agent.web.SearchResult;

public sealed interface NetworkEgressCapability extends Capability
        permits NetworkEgressCapabilityImpl {
    @NonNull String searchProviderName();

    @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception;

    @NonNull WebReadCapability openReader(@NonNull URI uri);
}
