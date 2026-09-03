package top.focess.veto.agent.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/** In-memory rendezvous between ask_user tool calls and authenticated UI responses. */
@Service
public final class UserQuestionRegistry {

    private final @NonNull ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public @NonNull CompletableFuture<AnswerBatch> register(
            @NonNull String agentId,
            @NonNull String callId,
            @NonNull List<AskUserTool.Question> questions) {
        CompletableFuture<AnswerBatch> future = new CompletableFuture<>();
        Pending value = new Pending(agentId, callId, questions, future);
        if (pending.putIfAbsent(key(agentId, callId), value) != null) {
            throw new IllegalStateException("Question batch already pending: " + callId);
        }
        future.whenComplete((ignored, error) -> pending.remove(key(agentId, callId), value));
        return future;
    }

    public @NonNull List<Map<String, Object>> pendingFor(@NonNull String agentId) {
        List<Pending> matching =
                pending.values().stream()
                        .filter(value -> value.agentId().equals(agentId))
                        .sorted(java.util.Comparator.comparing(Pending::callId))
                        .toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Pending value : matching) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("callId", value.callId());
            item.put("questions", value.questions());
            result.add(item);
        }
        return result;
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
            @NonNull List<AskUserTool.Question> questions,
            @NonNull Map<@NonNull String, @NonNull String> answers) {
        if (answers.size() != questions.size()) return false;
        for (AskUserTool.Question question : questions) {
            String answer = answers.get(question.id());
            if (answer == null || answer.isBlank()) return false;
        }
        return true;
    }

    private static @NonNull String key(@NonNull String agentId, @NonNull String callId) {
        return agentId + "|" + callId;
    }

    private record Pending(
            @NonNull String agentId,
            @NonNull String callId,
            @NonNull List<AskUserTool.Question> questions,
            @NonNull CompletableFuture<AnswerBatch> future) {}

    public record AnswerBatch(
            @NonNull Map<@NonNull String, @NonNull String> answers, boolean cancelled) {}
}
