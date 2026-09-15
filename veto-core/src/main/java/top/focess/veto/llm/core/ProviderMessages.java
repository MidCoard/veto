package top.focess.veto.llm.core;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** The non-system message boundaries sent to a provider, retaining internal source identity. */
public final class ProviderMessages {
    private ProviderMessages() {}

    public static @NonNull List<List<ChatMessage>> groups(@NonNull VetoRequest request) {
        List<List<ChatMessage>> result = new ArrayList<>();
        if (request.messages().isEmpty())
            return List.of(List.of(ChatMessage.user(request.userPrompt())));
        if (request.providerType() == ProviderType.GEMINI) return geminiGroups(request);
        String previousRole = null;
        for (ChatMessage message : request.messages()) {
            if (message.role().equals("system")) continue;
            boolean anthropic = request.providerType() == ProviderType.ANTHROPIC;
            if (anthropic
                    && message.role().equals("assistant")
                    && message.content().isEmpty()
                    && (message.toolName() == null || message.callId() == null)) continue;
            String role = message.role().equals("assistant") ? "assistant" : "user";
            if (anthropic && role.equals(previousRole)) {
                result.getLast().add(message);
            } else {
                result.add(new ArrayList<>(List.of(message)));
            }
            previousRole = role;
        }
        return result.stream().map(List::copyOf).toList();
    }

    private static @NonNull List<List<ChatMessage>> geminiGroups(@NonNull VetoRequest request) {
        var messages = request.messages().stream().filter(m -> !m.role().equals("system")).toList();
        List<List<ChatMessage>> out = new ArrayList<>();
        for (int i = 0; i < messages.size(); ) {
            var first = messages.get(i);
            var state = first.nativeState();
            if (state == null
                    || !state.model().equals(request.modelName())
                    || !first.role().equals("assistant")) {
                out.add(List.of(first));
                i++;
                continue;
            }
            List<ChatMessage> calls = new ArrayList<>();
            List<ChatMessage> results = new ArrayList<>();
            // Runtime persists call/result pairs in execution order. Reassemble one native batch.
            while (i < messages.size()) {
                var call = messages.get(i);
                var next = call.nativeState();
                if (next == null
                        || !next.batch().equals(state.batch())
                        || !next.model().equals(state.model())) break;
                calls.add(call);
                i++;
                if (i < messages.size()
                        && messages.get(i).role().equals("tool")
                        && java.util.Objects.equals(call.callId(), messages.get(i).callId()))
                    results.add(messages.get(i++));
                else break;
            }
            out.add(List.copyOf(calls));
            if (!results.isEmpty()) out.add(List.copyOf(results));
        }
        return List.copyOf(out);
    }
}
