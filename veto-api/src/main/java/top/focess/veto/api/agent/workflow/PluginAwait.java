package top.focess.veto.api.agent.workflow;

import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;

/**
 * Plugin-owned one-shot asynchronous work readiness signal bound by the host to the invoking
 * request.
 *
 * @param token plugin-local correlation token
 * @param ready future completed with {@code true} when work is available or {@code false} when the
 *     wait ends without work
 */
public record PluginAwait(@NonNull String token, @NonNull CompletableFuture<Boolean> ready) {}
