package top.focess.veto.llm.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.loop.PromptLibrary;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/** Shared response-channel rules; validation finishes before any call reaches the runtime. */
final class NativeToolResponses {
    private NativeToolResponses() {}

    record Call(@NonNull String name, @NonNull JsonNode args, String id) {}

    static boolean enabled(@NonNull VetoRequest request) {
        return request.nativeToolsEnabled() && !request.tools().isEmpty();
    }

    static @NonNull String prompt(@NonNull VetoRequest request) {
        return PromptLibrary.compile(
                        "provider-native",
                        Map.of("system", request.systemPrompt(), "nativeCalls", enabled(request)))
                .text();
    }

    static @NonNull JsonNode arguments(@NonNull ObjectMapper mapper, @NonNull String value) {
        try {
            JsonNode node =
                    mapper.reader()
                            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(value);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return node;
        } catch (Exception e) {
            throw new ModelSchemaException("Native tool arguments must be a valid JSON object");
        }
    }

    static void validateNativeChannel(
            @NonNull ObjectMapper mapper, @NonNull VetoRequest request, @NonNull String text) {
        if (!enabled(request))
            throw new ModelSchemaException("This turn does not permit native tool calls");
    }

    /** Native calls are adapter-owned data, never deserialized from the model's JSON text. */
    static LlmClient.@NonNull RawCompletion completion(
            @NonNull ObjectMapper mapper,
            @NonNull String summary,
            @NonNull String normalized,
            @NonNull List<Call> nativeCalls,
            @NonNull List<top.focess.veto.llm.core.NativeToolState> states) {
        if (nativeCalls.isEmpty()) return new LlmClient.RawCompletion(summary, normalized);
        var calls = new java.util.ArrayList<top.focess.veto.llm.core.ToolCall>();
        for (var call : nativeCalls) {
            Map<@NonNull String, Object> args =
                    mapper.convertValue(
                            call.args(),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    Map<@NonNull String, Object>>() {});
            calls.add(new top.focess.veto.llm.core.ToolCall(call.name(), args, call.id()));
        }
        return new LlmClient.RawCompletion(summary, normalized, states, calls);
    }

    static @NonNull String normalize(
            @NonNull ObjectMapper mapper,
            @NonNull VetoRequest request,
            @NonNull String text,
            @NonNull List<Call> nativeCalls) {
        if (nativeCalls.isEmpty()) return text;
        validateNativeChannel(mapper, request, text);
        var ids = new HashSet<String>();
        for (var call : nativeCalls) {
            if (request.tools().stream().noneMatch(tool -> tool.name().equals(call.name())))
                throw new ModelSchemaException(
                        "Tool is not available in this turn: " + call.name());
            if (!call.args().isObject())
                throw new ModelSchemaException("Native tool input must be a JSON object");
            String id = call.id();
            if (id != null && !id.isBlank() && !ids.add(id))
                throw new ModelSchemaException("Duplicate native tool call id");
        }
        return text;
    }
}
