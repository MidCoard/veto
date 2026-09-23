package top.focess.veto.agent.capability;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.Capability;
import top.focess.veto.api.search.SearchOptions;
import top.focess.veto.api.search.SearchResult;

public sealed interface NetworkEgressCapability extends Capability
        permits NetworkEgressCapabilityImpl {
    @NonNull String readGitHubRepository(
            @NonNull String credentialRef,
            @NonNull String repositoryOwner,
            @NonNull String repositoryName);

    @NonNull String searchProviderName();

    @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception;

    @NonNull WebReadCapability openReader(@NonNull URI uri);
}
