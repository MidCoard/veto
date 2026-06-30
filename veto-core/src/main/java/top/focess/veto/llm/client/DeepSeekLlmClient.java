package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;

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

    private final @NonNull String baseUrl;
    private final @NonNull String apiKey;
    private final @NonNull String providerName;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull CapabilityTranslator capabilityTranslator;

    DeepSeekLlmClient(
            String baseUrl,
            String apiKey,
            String providerName,
            ObjectMapper objectMapper,
            CapabilityTranslator capabilityTranslator) {
        this.baseUrl = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "https://api.deepseek.com";
        this.apiKey = apiKey;
        this.providerName = providerName;
        this.objectMapper = objectMapper;
        this.capabilityTranslator = capabilityTranslator;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        JsonNode responseSchema =
                request.responseSchema() != null
                        ? request.responseSchema()
                        : capabilityTranslator.vetoResponseSchema(true, false);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", request.modelName());

            // DeepSeek only supports json_object; inject schema into system prompt
            String schemaJson =
                    objectMapper
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(responseSchema);
            body.put(
                    "messages",
                    List.of(
                            Map.of(
                                    "role",
                                    "system",
                                    "content",
                                    request.systemPrompt()
                                            + "\n\nIMPORTANT: You must respond with a valid JSON object matching this schema:\n"
                                            + schemaJson
                                            + "\nEnsure the 'json' keyword is mentioned in your reasoning."),
                            Map.of("role", "user", "content", request.userPrompt())));
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
            // DeepSeek v4 reasoning models may return reasoning_content instead of content
            if (content == null || content.isEmpty()) {
                content = (String) message.get("reasoning_content");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) responseMap.get("usage");
            if (usage != null) {
                Number prompt = (Number) usage.get("prompt_tokens");
                Number completion = (Number) usage.get("completion_tokens");
                if (prompt != null && completion != null) {
                    top.focess.veto.llm.core.LlmSystemUsage.set(
                            prompt.longValue(), completion.longValue());
                }
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
}
