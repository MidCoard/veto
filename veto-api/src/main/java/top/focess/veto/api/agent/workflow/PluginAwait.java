package top.focess.veto.api.agent.workflow;

import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;

/** A plugin-owned, one-shot readiness signal; the host binds it to the invoking request. */
public record PluginAwait(@NonNull String token, @NonNull CompletableFuture<Boolean> ready) {}
