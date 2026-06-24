package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.util.Map;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.schema.VetoPulseSchema;

/**
 * Adapter wrapping a Gemini {@link Client}. Uses native JSON mode with a Gemini-dialect response
 * schema. All Gemini SDK types are confined to this class.
 */
final class GeminiLlmClient extends LlmClient {

    private final Client sdkClient;
    private final ObjectMapper objectMapper;

    GeminiLlmClient(Client sdkClient, ObjectMapper objectMapper) {
        this.sdkClient = sdkClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public RawCompletion complete(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        Schema responseSchema = objectMapper.convertValue(responseSchemaMap(request), Schema.class);

        GenerateContentConfig.Builder configBuilder =
                GenerateContentConfig.builder()
                        .systemInstruction(
                                Content.fromParts(Part.fromText(systemInstruction(request))))
                        .responseMimeType("application/json")
                        .responseSchema(responseSchema);

        if (request.options().temperature() != null) {
            configBuilder.temperature(request.options().temperature().floatValue());
        }
        if (request.options().maxTokens() != null) {
            configBuilder.maxOutputTokens(request.options().maxTokens());
        }

        String contents = conversationForGemini(request);
        GenerateContentResponse response =
                sdkClient.models.generateContent(
                        request.modelName(), contents, configBuilder.build());

        String summary = "model=" + request.modelName() + ", tools=" + request.tools().size();
        return new RawCompletion(summary, response.text());
    }

    private String systemInstruction(VetoRequest request) {
        if (request.hasMessages()) {
            // The first system message (if any) is the system instruction; the rest go to contents.
            for (ChatMessage m : request.messages()) {
                if ("system".equals(m.role())) {
                    return m.content();
                }
            }
        }
        return request.systemPrompt();
    }

    private String conversationForGemini(VetoRequest request) {
        if (!request.hasMessages()) {
            return request.userPrompt();
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : request.messages()) {
            if ("system".equals(m.role())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(m.role()).append(": ").append(m.content());
        }
        return sb.length() == 0 ? request.userPrompt() : sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseSchemaMap(VetoRequest request) {
        JsonNode node = request.responseSchema();
        if (node != null && !node.isNull()) {
            return objectMapper.convertValue(node, Map.class);
        }
        return VetoPulseSchema.defaultAutonomousThoughtOn();
    }
}
