package top.focess.veto.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ApprovalReceipt;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.llm.LlmBinding;

/**
 * Caller-owned result and confirmation that execution can no longer produce effects.
 *
 * <p>Plugin waits, approval state and the episode ledger belong to the Agent execution thread.
 * External cancellation publishes only the volatile intent under the Runner admission monitor.
 * Result/settlement futures support concurrent observation. Queued cancellation can complete its
 * future on the cancelling caller, and stop can reject an active result on the stopping caller
 * before execution exits. Ordinary admitted completion callbacks execute on the Agent thread.
 */
public final class RequestHandle {
    final @NonNull Object owner;
    final @NonNull RequestEpisode episode;
    final LlmBinding binding;
    final @NonNull Locale locale;
    final @NonNull CompletableFuture<AgentResult> result = new CompletableFuture<>();
    final @NonNull CompletableFuture<Boolean> settled = new CompletableFuture<>();
    final @NonNull Map<String, ApprovalReceipt> approvalReceipts = new HashMap<>();
    final @NonNull Set<String> declinedCallSignatures = new HashSet<>();
    private final @NonNull Map<String, CompletableFuture<Boolean>> waits = new HashMap<>();

    void await(@NonNull PluginAwait signal, @NonNull Runnable wake) {
        if (result.isDone() || cancelled) {
            signal.ready().cancel(false);
            return;
        }
        var previous = waits.putIfAbsent(signal.token(), signal.ready());
        if (previous != null && previous != signal.ready())
            throw new IllegalStateException("Await token already registered");
        if (previous != null) return;
        signal.ready().whenComplete((value, failure) -> wake.run());
    }

    boolean awaiting() {
        var iterator = waits.values().iterator();
        while (iterator.hasNext()) {
            var signal = iterator.next();
            if (!signal.isDone()) continue;
            if (!Boolean.TRUE.equals(signal.join()))
                throw new IllegalStateException("Plugin wait did not complete successfully");
            iterator.remove();
        }
        return !waits.isEmpty();
    }

    boolean readyToResume() {
        return waits.values().stream().anyMatch(CompletableFuture::isDone);
    }

    void releaseWaits() {
        for (var signal : waits.values()) signal.cancel(false);
        waits.clear();
    }

    @NonNull String message = "";
    volatile boolean cancelled;
    boolean interruptSent;
    // Guarded by the Runner admission/interrupt monitor, distinct from execution settlement.
    boolean resultClaimed;
    // Resolved only by the runner for this request; never replaces the agent's identity.
    volatile AgentProfiles.Resolved configuration;

    RequestHandle(@NonNull Object owner) {
        this(owner, new RequestEpisode(UUID.randomUUID().toString(), -1));
    }

    RequestHandle(@NonNull Object owner, @NonNull RequestEpisode episode) {
        this(owner, episode, null, Locale.ENGLISH);
    }

    RequestHandle(
            @NonNull Object owner,
            @NonNull RequestEpisode episode,
            LlmBinding binding,
            @NonNull Locale locale) {
        this.owner = owner;
        this.episode = episode;
        this.binding = binding;
        this.locale = locale;
    }

    /** The id of the episode this request drives. */
    public @NonNull String requestId() {
        return episode.id();
    }

    /** The future completed with this request's {@link AgentResult} when the episode settles. */
    public @NonNull CompletableFuture<AgentResult> result() {
        return result;
    }

    /** Completes once execution can no longer produce effects for this request. */
    public @NonNull CompletableFuture<Boolean> settled() {
        return settled;
    }

    /**
     * Blocks until the request's result is available or the timeout elapses.
     *
     * @throws TimeoutException if the result is not available within {@code timeout}
     * @throws IllegalStateException if the episode failed with a non-result error
     */
    public @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException {
        try {
            return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            throw new IllegalStateException("Request failed", error.getCause());
        }
    }
}
