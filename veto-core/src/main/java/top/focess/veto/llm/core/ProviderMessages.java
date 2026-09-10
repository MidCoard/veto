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
}
