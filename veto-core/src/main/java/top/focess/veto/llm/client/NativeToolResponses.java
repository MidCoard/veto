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
        var schema = request.responseSchema();
        return !request.tools().isEmpty()
                && (schema == null || schema.path("properties").has("calls"));
    }

    static @NonNull String prompt(@NonNull VetoRequest request, @NonNull JsonNode schema) {
        return PromptLibrary.compile(
                        "provider-native",
                        Map.of(
                                "system",
                                request.systemPrompt(),
                                "schema",
                                schema,
                                "nativeCalls",
                                enabled(request),
                                "jsonCalls",
                                schema.path("properties").has("calls"),
                                "guide",
                                schema.path("properties").has("guide")))
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

    /**
     * Only a whole-response JSON envelope is a protocol candidate. Examples inside prose are data.
     */
    static @NonNull String responseCandidate(@NonNull String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```json\n")
                || trimmed.startsWith("```\n")
                || trimmed.startsWith("```json\r\n")
                || trimmed.startsWith("```\r\n")) {
            int newline = trimmed.indexOf('\n');
            int end = trimmed.indexOf("```", newline + 1);
            if (end > newline && trimmed.substring(end + 3).isBlank()) {
                return trimmed.substring(newline + 1, end).strip();
            }
        }
        return trimmed;
    }

    static void validateNativeChannel(
            @NonNull ObjectMapper mapper, @NonNull VetoRequest request, @NonNull String text) {
        String candidate = responseCandidate(text);
        if (candidate.stripLeading().startsWith("{") || candidate.stripLeading().startsWith("[")) {
            JsonNode envelope = arguments(mapper, candidate);
            if (envelope.hasNonNull("calls") || envelope.hasNonNull("guide"))
                throw new ModelSchemaException(
                        "Response mixed native tool calls with JSON calls or guide; return exactly one execution channel");
        }
        if (!enabled(request))
            throw new ModelSchemaException("This turn does not permit native tool calls");
    }

    static @NonNull String normalize(
            @NonNull ObjectMapper mapper,
            @NonNull VetoRequest request,
            @NonNull String text,
            @NonNull List<Call> nativeCalls) {
        String candidate = responseCandidate(text);
        if (nativeCalls.isEmpty()) return candidate;
        validateNativeChannel(mapper, request, text);
        var pulse = mapper.createObjectNode();
        var calls = pulse.putArray("calls");
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
            var item = calls.addObject();
            item.put("tool_name", call.name());
            item.set("args", call.args());
        }
        if (!text.isBlank()) pulse.put("thought", text);
        return pulse.toString();
    }
}
