package top.focess.veto.builtin.questions;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.FrontendContribution.Scope;
import top.focess.veto.api.plugin.contract.SessionLifecycle;

/** Plugin-owned, in-memory rendezvous. Only the host supplies invocation identities. */
public final class QuestionRuntime implements SessionLifecycle, AutoCloseable {
    private final PluginHost host;
    private final @NonNull ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();
    private boolean closed;

    /** Creates the rendezvous; {@code host} may be null in declaration-only setups. */
    public QuestionRuntime(PluginHost host) {
        this.host = host;
    }

    /**
     * Registers the questions for the current invocation and blocks until answered or cancelled.
     */
    public @NonNull AnswerBatch ask(@NonNull List<Question> questions) throws InterruptedException {
        var currentHost = host;
        if (currentHost == null) throw new IllegalStateException("Question host unavailable");
        var invocation = currentHost.invocation("ask_user");
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
    public synchronized @NonNull CompletableFuture<AnswerBatch> register(
            PluginHost.@NonNull Invocation invocation, @NonNull List<Question> questions) {
        if (closed) throw new IllegalStateException("Question runtime closed");
        var scope = new Scope(invocation.owner(), invocation.sessionId(), invocation.agentId());
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
    public @NonNull List<PendingQuestionBatch> pendingFor(@NonNull Scope scope) {
        return pending.values().stream()
                .filter(value -> value.key().scope().equals(scope))
                .sorted(Comparator.comparing(value -> value.key().callId()))
                .map(value -> new PendingQuestionBatch(value.key().callId(), value.questions()))
                .toList();
    }

    /** Completes a pending batch with validated answers; returns whether it settled. */
    public boolean answer(
            @NonNull Scope scope,
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
    public boolean cancel(@NonNull Scope scope, @NonNull String callId) {
        var value = pending.get(new Key(scope, callId));
        return value != null && value.future().complete(new AnswerBatch(Map.of(), true));
    }

    @Override
    public void onOwnerClosed(@NonNull String ownerId) {
        cancelWhere(scope -> scope.ownerId().equals(ownerId));
    }

    @Override
    public void onSessionClosed(@NonNull String ownerId, @NonNull String sessionId) {
        cancelWhere(
                scope -> scope.ownerId().equals(ownerId) && scope.sessionId().equals(sessionId));
    }

    @Override
    public void onAgentTerminated(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {
        var target = new Scope(ownerId, sessionId, agentId);
        cancelWhere(target::equals);
    }

    private synchronized void cancelWhere(@NonNull Predicate<Scope> matches) {
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

    private void invalidate(@NonNull Scope scope) {
        var currentHost = host;
        if (currentHost == null) return;
        try {
            currentHost.invalidate(scope.sessionId(), "interactions");
        } catch (IllegalStateException | SecurityException ignored) {
            // Revocation must still settle all waiters when the host rejects late notifications.
        }
    }

    private record Key(@NonNull Scope scope, @NonNull String callId) {}

    private record Pending(
            @NonNull Key key,
            @NonNull List<Question> questions,
            @NonNull CompletableFuture<AnswerBatch> future) {}
}
