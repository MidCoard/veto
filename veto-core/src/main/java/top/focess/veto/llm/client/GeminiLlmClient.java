package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;

/**
 * Adapter wrapping a Gemini {@link Client}. Uses native JSON mode with the per-turn {@code
 * veto_pulse} response schema. All Gemini SDK types are confined to this class.
 */
final class GeminiLlmClient extends LlmClient {

    private final Client sdkClient;
    private final ObjectMapper objectMapper;
    private final CapabilityTranslator capabilityTranslator;

    GeminiLlmClient(
            Client sdkClient,
            ObjectMapper objectMapper,
            CapabilityTranslator capabilityTranslator) {
        this.sdkClient = sdkClient;
        this.objectMapper = objectMapper;
        this.capabilityTranslator = capabilityTranslator;
    }

    @Override
    public RawCompletion complete(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        // The per-turn veto_pulse schema (default thought-ON, autonomous until Part 1 wires
        // per-turn
        // flags).
        Schema responseSchema =
                objectMapper.convertValue(
                        capabilityTranslator.vetoResponseSchema(true, false), Schema.class);

        GenerateContentConfig.Builder configBuilder =
                GenerateContentConfig.builder()
                        .systemInstruction(Content.fromParts(Part.fromText(request.systemPrompt())))
                        .responseMimeType("application/json")
                        .responseSchema(responseSchema);

        if (request.options().temperature() != null) {
            configBuilder.temperature(request.options().temperature().floatValue());
        }
        if (request.options().maxTokens() != null) {
            configBuilder.maxOutputTokens(request.options().maxTokens());
        }

        GenerateContentResponse response =
                sdkClient.models.generateContent(
                        request.modelName(), request.userPrompt(), configBuilder.build());

        String summary = "model=" + request.modelName() + ", tools=" + request.tools().size();
        return new RawCompletion(summary, response.text());
    }
}
