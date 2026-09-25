package top.focess.veto.api.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/** The non-system message boundaries sent to a provider, retaining internal source identity. */
public final class ProviderMessages {
    private ProviderMessages() {}

    /**
     * Groups non-system messages into the boundaries required by the selected provider protocol.
     * Opaque native-state batches retain their original call/result order.
     *
     * @param request compiled provider request
     * @return immutable ordered message groups
     */
    public static @NonNull List<List<ChatMessage>> groups(@NonNull VetoRequest request) {
        List<List<ChatMessage>> result = new ArrayList<>();
        if (request.messages().isEmpty())
            return List.of(List.of(ChatMessage.user(request.userPrompt())));
        if (request.messages().stream().anyMatch(message -> message.nativeState() != null)) {
            var groups = nativeGroups(request);
            if (request.providerType() != ProviderType.ANTHROPIC) return groups;
            for (var group : groups) {
                var nonEmpty =
                        group.stream()
                                .filter(
                                        message ->
                                                !message.role().equals("assistant")
                                                        || !message.content().isEmpty()
                                                        || message.toolName() != null)
                                .toList();
                if (nonEmpty.isEmpty()) continue;
                boolean assistant = nonEmpty.getFirst().role().equals("assistant");
                if (!result.isEmpty()
                        && result.getLast().getFirst().role().equals("assistant") == assistant)
                    result.getLast().addAll(nonEmpty);
                else result.add(new ArrayList<>(nonEmpty));
            }
            return result.stream().map(List::copyOf).toList();
        }
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

    private static @NonNull List<List<ChatMessage>> nativeGroups(@NonNull VetoRequest request) {
        var messages = request.messages().stream().filter(m -> !m.role().equals("system")).toList();
        List<List<ChatMessage>> out = new ArrayList<>();
        for (int i = 0; i < messages.size(); ) {
            var first = messages.get(i);
            var state = first.nativeState();
            if (state == null
                    || !state.supports(request.providerType().name(), request.modelName())
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
                        || !next.supports(request.providerType().name(), request.modelName()))
                    break;
                calls.add(call);
                i++;
                if (i < messages.size()
                        && messages.get(i).role().equals("tool")
                        && Objects.equals(call.callId(), messages.get(i).callId()))
                    results.add(messages.get(i++));
                else break;
            }
            out.add(List.copyOf(calls));
            if (!results.isEmpty()) out.add(List.copyOf(results));
        }
        return List.copyOf(out);
    }
}
