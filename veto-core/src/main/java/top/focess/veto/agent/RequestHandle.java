package top.focess.veto.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
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

/** Caller-owned result and confirmation that execution can no longer produce effects. */
public final class RequestHandle {
    final @NonNull Object owner;
    final @NonNull RequestEpisode episode;
    final @NonNull Result result = new Result();
    final @NonNull CompletableFuture<Boolean> settled = new CompletableFuture<>();
    final @NonNull Map<String, ApprovalReceipt> approvalReceipts = new HashMap<>();
    final @NonNull Set<String> declinedCallSignatures = new HashSet<>();
    private final @NonNull Map<String, CompletableFuture<Boolean>> waits = new HashMap<>();

    synchronized void await(@NonNull PluginAwait signal, @NonNull Runnable wake) {
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

    synchronized boolean awaiting() {
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

    synchronized boolean readyToResume() {
        return waits.values().stream().anyMatch(CompletableFuture::isDone);
    }

    synchronized void releaseWaits() {
        for (var signal : waits.values()) signal.cancel(false);
        waits.clear();
    }

    @NonNull String message = "";
    volatile boolean cancelled;
    boolean interruptSent;
    @NonNull String requestId;

    RequestHandle(@NonNull Object owner) {
        this(owner, new RequestEpisode(UUID.randomUUID().toString(), -1));
    }

    RequestHandle(@NonNull Object owner, @NonNull RequestEpisode episode) {
        this.owner = owner;
        this.episode = episode;
        this.requestId = episode.id();
    }

    final class Result extends CompletableFuture<AgentResult> {
        @NonNull RequestHandle handle() {
            return RequestHandle.this;
        }
    }

    public @NonNull String requestId() {
        return requestId;
    }

    public @NonNull CompletableFuture<AgentResult> result() {
        return result;
    }

    public @NonNull CompletableFuture<Boolean> settled() {
        return settled;
    }

    public @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException {
        try {
            return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            throw new IllegalStateException("Request failed", error.getCause());
        }
    }
}
