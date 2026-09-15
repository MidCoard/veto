package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.*;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/**
 * GenerateContent native functions with JSON calls/guide compatibility and durable signed parts.
 */
final class GeminiLlmClient extends LlmClient {
    private final @NonNull Client sdkClient;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull CapabilityTranslator capabilityTranslator;

    GeminiLlmClient(
            @NonNull Client sdkClient,
            @NonNull ObjectMapper objectMapper,
            @NonNull CapabilityTranslator capabilityTranslator) {
        this.sdkClient = sdkClient;
        this.objectMapper = objectMapper;
        this.capabilityTranslator = capabilityTranslator;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        var request = resolved.request();
        var configured = request.responseSchema();
        var schema =
                configured != null ? configured : capabilityTranslator.vetoResponseSchema(false);
        var config =
                GenerateContentConfig.builder()
                        .systemInstruction(
                                Content.fromParts(
                                        Part.fromText(
                                                NativeToolResponses.prompt(request, schema))));
        if (NativeToolResponses.enabled(request)) {
            var declarations =
                    request.tools().stream()
                            .map(
                                    tool ->
                                            FunctionDeclaration.builder()
                                                    .name(tool.name())
                                                    .description(tool.description())
                                                    .parametersJsonSchema(tool.inputSchema())
                                                    .build())
                            .toList();
            config.tools(Tool.builder().functionDeclarations(declarations).build())
                    .toolConfig(
                            ToolConfig.builder()
                                    .functionCallingConfig(
                                            FunctionCallingConfig.builder().mode("AUTO").build())
                                    .build());
            // JSON response MIME/schema and function calling are not composable on all supported
            // Gemini models. Keep the JSON contract in the prompt on native-tool turns.
        } else {
            config.responseMimeType("application/json")
                    .responseSchema(Schema.fromJson(schema.toString()));
        }
        Double temperature = request.options().temperature();
        if (temperature != null) config.temperature(temperature.floatValue());
        Integer maxTokens = request.options().maxTokens();
        if (maxTokens != null) config.maxOutputTokens(maxTokens);
        var response =
                sdkClient.models.generateContent(
                        request.modelName(), conversationContents(request), config.build());
        response.usageMetadata()
                .ifPresent(
                        usage ->
                                LlmSystemUsage.set(
                                        usage.promptTokenCount().map(Number::longValue).orElse(0L),
                                        usage.candidatesTokenCount()
                                                .map(Number::longValue)
                                                .orElse(0L),
                                        usage.cachedContentTokenCount()
                                                .map(Number::longValue)
                                                .orElse(null),
                                        null));
        var candidates = response.candidates().orElse(List.of());
        if (candidates.isEmpty())
            throw new ModelCapabilityException("Gemini returned no candidates");
        if (candidates
                .getFirst()
                .finishReason()
                .map(Object::toString)
                .orElse("")
                .equals("MAX_TOKENS"))
            throw new ModelSchemaException(
                    "Gemini returned an incomplete response; no calls were executed");
        var parts = candidates.getFirst().content().flatMap(Content::parts).orElse(List.of());
        var calls = new ArrayList<NativeToolResponses.Call>();
        var segments = new ArrayList<List<Part>>();
        var pending = new ArrayList<Part>();
        var text = new ArrayList<String>();
        for (var part : parts) {
            if (part.toolCall().isPresent())
                throw new ModelSchemaException("Unsupported Gemini native tool call type");
            pending.add(part);
            if (!part.thought().orElse(false)) part.text().ifPresent(text::add);
            if (part.functionCall().isPresent()) {
                var call = part.functionCall().get();
                calls.add(
                        new NativeToolResponses.Call(
                                call.name()
                                        .orElseThrow(
                                                () ->
                                                        new ModelSchemaException(
                                                                "Missing Gemini function name")),
                                objectMapper.valueToTree(call.args().orElse(Map.of())),
                                call.id().orElse(null)));
                segments.add(new ArrayList<>(pending));
                pending.clear();
            }
        }
        if (!segments.isEmpty()) segments.getLast().addAll(pending);
        String normalized =
                NativeToolResponses.normalize(
                        objectMapper, request, String.join("\n", text), calls);
        if (normalized.isBlank())
            throw new ModelCapabilityException("Gemini returned neither text nor functions");
        var states = new ArrayList<NativeToolState>();
        String batch = UUID.randomUUID().toString();
        for (var segment : segments) {
            // SDK JSON round-trip retains bytes and original part boundaries, including signatures.
            states.add(
                    new NativeToolState(
                            request.modelName(),
                            batch,
                            Content.builder().role("model").parts(segment).build().toJson(),
                            states.size()));
        }
        return NativeToolResponses.completion(
                objectMapper,
                "model=" + request.modelName() + ", tools=" + request.tools().size(),
                normalized,
                calls,
                states);
    }

    private @NonNull List<Content> conversationContents(@NonNull VetoRequest request) {
        var result = new ArrayList<Content>();
        var nativeCalls = new HashMap<String, FunctionCall>();
        for (var group : ProviderMessages.groups(request)) {
            var headState = group.getFirst().nativeState();
            // A compacted/rewound tail may omit the signed beginning of a batch. Render that
            // historical tail as JSON text instead of inventing or reusing a misplaced signature.
            boolean replayNative = headState != null && headState.position() == 0;
            var parts = new ArrayList<Part>();
            for (var message : group) {
                var state = message.nativeState();
                String callId = message.callId();
                if (state != null
                        && state.model().equals(request.modelName())
                        && callId != null
                        && replayNative) {
                    var original = Content.fromJson(state.partsJson()).parts().orElse(List.of());
                    for (var part : original)
                        part.functionCall().ifPresent(call -> nativeCalls.put(callId, call));
                    parts.addAll(original);
                } else if (message.role().equals("tool")
                        && callId != null
                        && nativeCalls.containsKey(callId)) {
                    var call = nativeCalls.get(callId);
                    if (call == null)
                        throw new IllegalStateException("Missing native history call");
                    var response =
                            FunctionResponse.builder()
                                    .name(call.name().orElseThrow())
                                    .response(
                                            Map.of(
                                                    "content",
                                                    message.content(),
                                                    "success",
                                                    !Boolean.FALSE.equals(message.toolSuccess())));
                    call.id().ifPresent(response::id);
                    parts.add(Part.builder().functionResponse(response.build()).build());
                } else {
                    // Legacy, JSON and guided calls have no signed native part. Keep them as text;
                    // do not synthesize a signature or a functionCall that Gemini 3 would reject.
                    parts.add(Part.fromText(renderHistoryMessage(message)));
                }
            }
            result.add(
                    Content.builder()
                            .role(group.getFirst().role().equals("assistant") ? "model" : "user")
                            .parts(parts)
                            .build());
        }
        return List.copyOf(result);
    }

    private @NonNull String renderHistoryMessage(@NonNull ChatMessage message) {
        if (!message.role().equals("assistant")) return message.content();
        var root = objectMapper.createObjectNode();
        if (message.callId() != null) {
            var call = root.putArray("calls").addObject();
            String name = message.toolName();
            call.put("tool_name", name == null ? "" : name);
            String args = message.toolArgs();
            call.set(
                    "args",
                    NativeToolResponses.arguments(objectMapper, args == null ? "{}" : args));
            if (!message.content().isEmpty()) root.put("thought", message.content());
        } else root.put("message", message.content());
        return root.toString();
    }
}
