package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.schema.VetoPulseSchema;

/**
 * Adapter for DeepSeek and other OpenAI-compatible providers that speaks pure REST JSON — no OpenAI
 * SDK dependency. Avoids the SDK's Jackson version check entirely.
 *
 * <p>Uses {@code java.net.http.HttpClient} (JDK built-in, no extra dependency) to POST to {@code
 * /v1/chat/completions} with standard chat completion JSON.
 */
final class DeepSeekLlmClient extends LlmClient {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String baseUrl;
    private final String apiKey;
    private final String providerName;
    private final ObjectMapper objectMapper;

    DeepSeekLlmClient(
            String baseUrl, String apiKey, String providerName, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "https://api.deepseek.com";
        this.apiKey = apiKey;
        this.providerName = providerName;
        this.objectMapper = objectMapper;
    }

    @Override
    public RawCompletion complete(ResolvedRequest resolved) {
        VetoRequest request = resolved.request();

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", request.modelName());
            body.put("messages", buildMessages(request));

            // DeepSeek only supports json_object; inject the veto_pulse schema into the system
            // prompt.
            String schemaJson =
                    objectMapper
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(responseSchemaMap(request));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> msgs = (List<Map<String, Object>>) body.get("messages");
            if (!msgs.isEmpty() && "system".equals(msgs.get(0).get("role"))) {
                msgs.get(0)
                        .put(
                                "content",
                                msgs.get(0).get("content")
                                        + "\n\nIMPORTANT: You must respond with a valid JSON object matching this schema:\n"
                                        + schemaJson);
            }
            body.put("response_format", Map.of("type", "json_object"));

            LlmOptions options = request.options();
            if (options.temperature() != null) {
                body.put("temperature", options.temperature());
            }
            if (options.topP() != null) {
                body.put("top_p", options.topP());
            }
            if (options.maxTokens() != null) {
                body.put("max_tokens", options.maxTokens());
            }

            String json = objectMapper.writeValueAsString(body);
            HttpRequest httpRequest =
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/v1/chat/completions"))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(options.timeoutOrDefault().toSeconds()))
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build();

            HttpResponse<String> httpResponse =
                    HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {
                throw new ModelCapabilityException(
                        providerName
                                + " returned HTTP "
                                + httpResponse.statusCode()
                                + ": "
                                + httpResponse.body());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap =
                    objectMapper.readValue(httpResponse.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) responseMap.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new ModelCapabilityException(providerName + " returned empty choices");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                throw new ModelCapabilityException(providerName + " returned empty message");
            }

            String content = (String) message.get("content");
            if (content == null || content.isEmpty()) {
                content = (String) message.get("reasoning_content");
            }
            if (content == null || content.isEmpty()) {
                throw new ModelCapabilityException(
                        providerName
                                + " returned empty content. Full response: "
                                + httpResponse.body());
            }

            String summary =
                    "model="
                            + request.modelName()
                            + ", tools="
                            + request.tools().size()
                            + ", via=rest";
            return new RawCompletion(summary, content);
        } catch (ModelCapabilityException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelCapabilityException(
                    providerName + " REST call failed: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> buildMessages(VetoRequest request) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        if (request.hasMessages()) {
            for (ChatMessage m : request.messages()) {
                msgs.add(Map.of("role", m.role(), "content", m.content()));
            }
        } else {
            msgs.add(Map.of("role", "system", "content", request.systemPrompt()));
            msgs.add(Map.of("role", "user", "content", request.userPrompt()));
        }
        return msgs;
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
