package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;

/**
 * Adapter wrapping a Gemini {@link Client}. Uses native JSON mode with the per-turn {@code
 * veto_pulse} response schema. All Gemini SDK types are confined to this class.
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
        VetoRequest request = resolved.request();
        JsonNode configuredSchema = request.responseSchema();
        JsonNode rawSchema =
                configuredSchema != null
                        ? configuredSchema
                        : capabilityTranslator.vetoResponseSchema(false);
        Schema responseSchema = objectMapper.convertValue(rawSchema, Schema.class);

        GenerateContentConfig.Builder configBuilder =
                GenerateContentConfig.builder()
                        .systemInstruction(Content.fromParts(Part.fromText(request.systemPrompt())))
                        .responseMimeType("application/json")
                        .responseSchema(responseSchema);

        Double temperature = request.options().temperature();
        if (temperature != null) {
            configBuilder.temperature(temperature.floatValue());
        }
        Integer maxTokens = request.options().maxTokens();
        if (maxTokens != null) {
            configBuilder.maxOutputTokens(maxTokens);
        }

        List<Content> contents = conversationContents(request);
        GenerateContentResponse response =
                sdkClient.models.generateContent(
                        request.modelName(), contents, configBuilder.build());
        if (response.usageMetadata().isPresent()) {
            var usage = response.usageMetadata().get();
            long prompt = usage.promptTokenCount().map(Number::longValue).orElse(0L);
            long candidates = usage.candidatesTokenCount().map(Number::longValue).orElse(0L);
            top.focess.veto.llm.core.LlmSystemUsage.set(prompt, candidates);
        }

        String text = response.text();
        if (text == null) {
            throw new ModelCapabilityException("Gemini returned a response without text");
        }
        String summary = "model=" + request.modelName() + ", tools=" + request.tools().size();
        return new RawCompletion(summary, text);
    }

    private @NonNull List<Content> conversationContents(@NonNull VetoRequest request) {
        List<ChatMessage> history = request.messages();
        if (history.isEmpty()) {
            return List.of(Content.fromParts(Part.fromText(request.userPrompt())));
        }
        List<Content> contents = new ArrayList<>();
        for (ChatMessage message : history) {
            if ("system".equals(message.role())) {
                continue;
            }
            String role = "assistant".equals(message.role()) ? "model" : "user";
            contents.add(
                    Content.builder()
                            .role(role)
                            .parts(Part.fromText(renderHistoryMessage(message)))
                            .build());
        }
        return List.copyOf(contents);
    }

    private @NonNull String renderHistoryMessage(@NonNull ChatMessage message) {
        if ("tool".equals(message.role())) {
            return message.toolResultContentWithStatus();
        }
        if (!"assistant".equals(message.role())) {
            return message.content();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        if (message.callId() != null) {
            Map<String, Object> call = new LinkedHashMap<>();
            String toolName = message.toolName();
            call.put("tool_name", toolName != null ? toolName : "");
            call.put("args", parseArgs(message.toolArgs()));
            if (!message.content().isEmpty()) {
                response.put("thought", message.content());
            }
            response.put("calls", List.of(call));
        } else {
            response.put("message", message.content());
        }
        response.put("features", Map.of("guided", false));
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new ModelCapabilityException("Could not encode Gemini conversation history", e);
        }
    }

    private @NonNull Object parseArgs(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawArgs, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
