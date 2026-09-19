package top.focess.veto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import top.focess.veto.llm.core.VetoRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/** Durable comparison data only: no prompt text, tool arguments, or credentials. */
public record UsageCheckpoint(
        @NonNull String contextHash,
        @NonNull List<String> messageHashes,
        long inputTokens,
        long outputTokens) {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    public UsageCheckpoint {
        messageHashes = List.copyOf(messageHashes);
    }

    private record Context(
            @NonNull String provider,
            @NonNull String model,
            @Nullable String baseUrl,
            @NonNull String system,
            @NonNull Object tools,
            @Nullable Object schema,
            boolean nativeTools) {}

    private record Message(
            top.focess.veto.llm.core.@NonNull ChatMessage content,
            top.focess.veto.llm.core.@Nullable NativeToolState nativeState) {}

    public static @NonNull UsageCheckpoint capture(
            @NonNull VetoRequest request, long input, long output) {
        return new UsageCheckpoint(
                hash(
                        new Context(
                                request.providerType().name(),
                                request.modelName(),
                                request.baseUrl(),
                                request.systemPrompt(),
                                request.tools(),
                                request.responseSchema(),
                                request.nativeToolsEnabled())),
                request.messages().stream()
                        .map(message -> hash(new Message(message, message.nativeState())))
                        .toList(),
                input,
                output);
    }

    public boolean precedes(@NonNull UsageCheckpoint next) {
        return contextHash.equals(next.contextHash)
                && messageHashes.size() <= next.messageHashes.size()
                && messageHashes.equals(next.messageHashes.subList(0, messageHashes.size()));
    }

    private static @NonNull String hash(@NonNull Object value) {
        try {
            JsonNode node = JSON.valueToTree(value);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical(node).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot fingerprint usage baseline", failure);
        }
    }

    private static @NonNull String canonical(@NonNull JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, String>();
            node.properties().forEach(e -> fields.put(e.getKey(), canonical(e.getValue())));
            var out = new StringBuilder("{");
            fields.forEach(
                    (key, value) ->
                            out.append(JSON.getNodeFactory().textNode(key))
                                    .append(':')
                                    .append(value)
                                    .append(','));
            return out.append('}').toString();
        }
        if (node.isArray()) {
            var out = new StringBuilder("[");
            node.forEach(value -> out.append(canonical(value)).append(','));
            return out.append(']').toString();
        }
        return node.toString();
    }
}
