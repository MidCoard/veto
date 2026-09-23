package top.focess.veto.agent.tool.builtin;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.focess.veto.api.interaction.AnswerBatch;
import top.focess.veto.api.interaction.Question;
import top.focess.veto.bus.SessionInvalidations;

/** In-memory rendezvous between ask_user tool calls and authenticated UI responses. */
@Service
public final class UserQuestionRegistry {
    private SessionInvalidations invalidations;

    @Autowired
    public void attachInvalidations(@NonNull SessionInvalidations invalidations) {
        this.invalidations = invalidations;
    }

    private final @NonNull ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();

    public @NonNull CompletableFuture<AnswerBatch> register(
            @NonNull String agentId, @NonNull String callId, @NonNull List<Question> questions) {
        CompletableFuture<AnswerBatch> future = new CompletableFuture<>();
        List<Question> snapshot =
                questions.stream()
                        .map(
                                question ->
                                        new Question(
                                                question.header(),
                                                question.id(),
                                                question.question(),
                                                List.copyOf(question.options())))
                        .toList();
        Pending value = new Pending(agentId, callId, snapshot, future);
        if (pending.putIfAbsent(key(agentId, callId), value) != null) {
            throw new IllegalStateException("Question batch already pending: " + callId);
        }
        if (invalidations != null) invalidations.agentChanged(agentId, "interactions");
        future.whenComplete(
                (ignored, error) -> {
                    pending.remove(key(agentId, callId), value);
                    if (invalidations != null) invalidations.agentChanged(agentId, "interactions");
                });
        return future;
    }

    public record PendingQuestionBatch(@NonNull String callId, @NonNull List<Question> questions) {
        public PendingQuestionBatch {
            questions = List.copyOf(questions);
        }
    }

    public @NonNull List<PendingQuestionBatch> pendingFor(@NonNull String agentId) {
        return pending.values().stream()
                .filter(value -> value.agentId().equals(agentId))
                .sorted(Comparator.comparing(Pending::callId))
                .map(value -> new PendingQuestionBatch(value.callId(), value.questions()))
                .toList();
    }

    public boolean answer(
            @NonNull String agentId,
            @NonNull String callId,
            @NonNull Map<@NonNull String, @NonNull String> answers) {
        Pending value = pending.get(key(agentId, callId));
        if (value == null || !validAnswers(value.questions(), answers)) return false;
        return value.future().complete(new AnswerBatch(Map.copyOf(answers), false));
    }

    public boolean cancel(@NonNull String agentId, @NonNull String callId) {
        Pending value = pending.get(key(agentId, callId));
        return value != null && value.future().complete(new AnswerBatch(Map.of(), true));
    }

    private static boolean validAnswers(
            @NonNull List<Question> questions,
            @NonNull Map<@NonNull String, @NonNull String> answers) {
        if (answers.size() != questions.size()) return false;
        for (Question question : questions) {
            String answer = answers.get(question.id());
            if (answer == null
                    || answer.isBlank()
                    || answer.codePointCount(0, answer.length()) > 500) return false;
        }
        return true;
    }

    private static @NonNull Key key(@NonNull String agentId, @NonNull String callId) {
        return new Key(agentId, callId);
    }

    private record Key(@NonNull String agentId, @NonNull String callId) {}

    private record Pending(
            @NonNull String agentId,
            @NonNull String callId,
            @NonNull List<Question> questions,
            @NonNull CompletableFuture<AnswerBatch> future) {}
}
