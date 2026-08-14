package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.translation.CapabilityTranslator;
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

        GenerateContentResponse response =
                sdkClient.models.generateContent(
                        request.modelName(), request.userPrompt(), configBuilder.build());
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
}
