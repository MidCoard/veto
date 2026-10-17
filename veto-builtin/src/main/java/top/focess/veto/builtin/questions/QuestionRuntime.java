package top.focess.veto.builtin.questions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;

/**
 * Thread-safe, plugin-owned, in-memory rendezvous. Only the host supplies invocation identities.
 *
 * <p>Admitted tool calls, frontend answers and lifecycle cancellation may run concurrently. The
 * runtime monitor owns pending batches, revisions and ordered state publications. Atomic future
 * completion settles a batch at most once. The asking thread waits outside the monitor. Completing
 * a future may run its callbacks inline, including state publication; callbacks must not block
 * waiting for another thread to register or close on the same monitor. Returned snapshots contain
 * only the requested owner, session and agent's pending batches.
 */
public final class QuestionRuntime implements Listener, AutoCloseable {
    private final @NonNull PluginHost host;
    private final @NonNull Map<QuestionInvocation, PendingQuestions> pending = new HashMap<>();
    private final @NonNull ObjectMapper mapper = new ObjectMapper();
    private long revision;
    private boolean closed;

    /** Creates the rendezvous with its invocation and state publication host. */
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
        var key = new QuestionInvocation(scope, invocation.callId());
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
        var value = new PendingQuestions(key, snapshot, future);
        if (pending.putIfAbsent(key, value) != null)
            throw new IllegalStateException(
                    "Question batch already pending: " + invocation.callId());
        future.whenComplete(
                (ignored, error) -> {
                    synchronized (this) {
                        if (pending.remove(key, value)) {
                            revision++;
                            publishQuestionState(scope);
                        }
                    }
                });
        try {
            revision++;
            publishQuestionState(scope);
            return future;
        } catch (Throwable failure) {
            future.cancel(false);
            throw failure;
        }
    }

    /** A registered batch awaiting answers, as shown by the frontend. */
    public record PendingQuestionBatch(@NonNull String callId, @NonNull List<Question> questions) {
        public PendingQuestionBatch {
            questions = List.copyOf(questions);
        }
    }

    /** Returns the pending batches for the scope, ordered by call id. */
    public synchronized @NonNull List<PendingQuestionBatch> pendingFor(
            Scope.@NonNull AgentScope scope) {
        return pending.values().stream()
                .filter(value -> value.key().scope().equals(scope))
                .sorted(Comparator.comparing(value -> value.key().callId()))
                .map(value -> new PendingQuestionBatch(value.key().callId(), value.questions()))
                .toList();
    }

    /** Returns the same agent state and revision used by live question actions. */
    public synchronized JsonValue.@NonNull ObjectValue snapshot(Scope.@NonNull AgentScope scope) {
        return new JsonValue.ObjectValue(
                Map.of(
                        "agentId", new JsonValue.StringValue(scope.agent()),
                        "revision", new JsonValue.NumberValue(BigDecimal.valueOf(revision)),
                        "items", JsonValues.from(mapper.valueToTree(pendingFor(scope)))));
    }

    /** Completes a pending batch with validated answers; returns whether it settled. */
    public synchronized boolean answer(
            Scope.@NonNull AgentScope scope,
            @NonNull String callId,
            @NonNull Map<@NonNull String, @NonNull String> answers) {
        var value = pending.get(new QuestionInvocation(scope, callId));
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
    public synchronized boolean cancel(Scope.@NonNull AgentScope scope, @NonNull String callId) {
        var value = pending.get(new QuestionInvocation(scope, callId));
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

    /** Publishes the complete agent state while the runtime monitor preserves revision order. */
    private void publishQuestionState(Scope.@NonNull AgentScope scope) {
        var state = snapshot(scope);
        try {
            host.publish(scope.sessionScope(), "questions_changed", state);
        } catch (IllegalStateException | SecurityException ignored) {
            // Revocation must still settle all waiters when the host rejects late publication.
        }
    }

    /** Immutable identity of one question tool invocation. */
    private record QuestionInvocation(Scope.@NonNull AgentScope scope, @NonNull String callId) {}

    /** Question data and answer completion; QuestionRuntime owns registration and settlement. */
    private record PendingQuestions(
            @NonNull QuestionInvocation key,
            @NonNull List<Question> questions,
            @NonNull CompletableFuture<AnswerBatch> future) {}
}
