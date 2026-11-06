package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Host credential and network-egress resolution, available to provider adapters. */
@FunctionalInterface
public interface LlmRequestResolver {
    /** Resolves the configured network target without exposing credentials on VetoRequest. */
    @NonNull ResolvedRequest resolve(@NonNull VetoRequest request, String defaultEndpoint);
}
