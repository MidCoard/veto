package top.focess.veto.builtin.questions;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;

/**
 * Plugin-owned, in-memory rendezvous. Only the host supplies invocation identities.
 *
 * <p>Admitted tool calls, frontend answers and lifecycle cancellation may run concurrently. The
 * runtime monitor serializes registration with close and bulk cancellation; concurrent lookup and
 * atomic future completion settle a batch at most once. The asking thread waits outside the
 * monitor. Completing a future may run its callbacks inline, including invalidation; callbacks must
 * not block waiting for another thread to register or close on the same monitor. Pending views are
 * weakly consistent snapshots rather than a transaction across all batches.
 */
public final class QuestionRuntime implements Listener, AutoCloseable {
    private final @NonNull PluginHost host;
    private final @NonNull ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();
    private boolean closed;

    /** Creates the rendezvous with its required invocation and invalidation host. */
    public QuestionRuntime(@NonNull PluginHost host) {
        this.host = host;
    }

    /**
     * Registers the questions for the current invocation and blocks until answered or cancelled.
     */
    public @NonNull AnswerBatch ask(@NonNull List<Question> questions) throws InterruptedException {
        var invocation = host.invocation("ask_user");
        var future = register(invocation, questions);
        try {
            return future.get();
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Question wait failed", failure.getCause());
        } finally {
            future.cancel(false);
        }
    }

    /** Registers a question batch for the invocation; fails when the call id is already pending. */
    synchronized @NonNull CompletableFuture<AnswerBatch> register(
            PluginHost.@NonNull Invocation invocation, @NonNull List<Question> questions) {
        if (closed) throw new IllegalStateException("Question runtime closed");
        var scope = invocation.scope();
        var key = new Key(scope, invocation.callId());
        var future = new CompletableFuture<AnswerBatch>();
        var snapshot =
                questions.stream()
                        .map(
                                question ->
                                        new Question(
                                                question.header(),
                                                question.id(),
                                                question.question(),
                                                List.copyOf(question.options())))
                        .toList();
        var value = new Pending(key, snapshot, future);
        if (pending.putIfAbsent(key, value) != null)
            throw new IllegalStateException(
                    "Question batch already pending: " + invocation.callId());
        future.whenComplete(
                (ignored, error) -> {
                    pending.remove(key, value);
                    invalidate(scope);
                });
        invalidate(scope);
        return future;
    }

    /** A registered batch awaiting answers, as shown by the frontend. */
    public record PendingQuestionBatch(@NonNull String callId, @NonNull List<Question> questions) {
        public PendingQuestionBatch {
            questions = List.copyOf(questions);
        }
    }

    /** Returns the pending batches for the scope, ordered by call id. */
    public @NonNull List<PendingQuestionBatch> pendingFor(Scope.@NonNull AgentScope scope) {
        return pending.values().stream()
                .filter(value -> value.key().scope().equals(scope))
                .sorted(Comparator.comparing(value -> value.key().callId()))
                .map(value -> new PendingQuestionBatch(value.key().callId(), value.questions()))
                .toList();
    }

    /** Completes a pending batch with validated answers; returns whether it settled. */
    public boolean answer(
            Scope.@NonNull AgentScope scope,
            @NonNull String callId,
            @NonNull Map<@NonNull String, @NonNull String> answers) {
        var value = pending.get(new Key(scope, callId));
        if (value == null || answers.size() != value.questions().size()) return false;
        for (var question : value.questions()) {
            var answer = answers.get(question.id());
            if (answer == null
                    || answer.isBlank()
                    || answer.codePointCount(0, answer.length()) > 500) return false;
        }
        return value.future().complete(new AnswerBatch(Map.copyOf(answers), false));
    }

    /** Completes a pending batch as cancelled; returns whether it settled. */
    public boolean cancel(Scope.@NonNull AgentScope scope, @NonNull String callId) {
        var value = pending.get(new Key(scope, callId));
        return value != null && value.future().complete(new AnswerBatch(Map.of(), true));
    }

    @EventHandler
    public void onUserLogout(@NonNull UserLogoutEvent event) {
        cancelWhere(scope -> scope.userScope().equals(event.scope()));
    }

    @EventHandler
    public void onSessionDeleted(@NonNull SessionDeletedEvent event) {
        cancelWhere(scope -> scope.sessionScope().equals(event.scope()));
    }

    @EventHandler
    public void onAgentTerminated(@NonNull AgentTerminatedEvent event) {
        var target = event.scope();
        cancelWhere(target::equals);
    }

    private synchronized void cancelWhere(@NonNull Predicate<Scope.AgentScope> matches) {
        for (var value : List.copyOf(pending.values())) {
            if (matches.test(value.key().scope()))
                value.future().complete(new AnswerBatch(Map.of(), true));
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancelWhere(scope -> true);
    }

    private void invalidate(Scope.@NonNull AgentScope scope) {
        try {
            host.invalidate(scope.sessionScope(), "interactions");
        } catch (IllegalStateException | SecurityException ignored) {
            // Revocation must still settle all waiters when the host rejects late notifications.
        }
    }

    private record Key(Scope.@NonNull AgentScope scope, @NonNull String callId) {}

    private record Pending(
            @NonNull Key key,
            @NonNull List<Question> questions,
            @NonNull CompletableFuture<AnswerBatch> future) {}
}
