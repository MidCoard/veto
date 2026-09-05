package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.web.WebFetchTool;
import top.focess.veto.agent.web.WebSearchTool;

public sealed interface NetworkEgressCapability extends Capability
        permits NetworkEgressCapabilityImpl {
    @NonNull String search(WebSearchTool.@NonNull Args args);

    @NonNull String fetch(WebFetchTool.@NonNull Args args);
}
