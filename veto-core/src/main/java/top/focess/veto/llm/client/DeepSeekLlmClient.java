package top.focess.veto.llm.client;

import com.fasterxml.jackson.core.type.TypeReference;
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
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;

/**
 * Adapter for DeepSeek's <b>Responses API</b> ({@code POST /responses}).
 *
 * <p>Uses the Responses API with {@code text.format: json_schema} for server-side schema
 * enforcement, which avoids the {@code response_format: json_object} blank-content bug in the Chat
 * Completions API (confirmed DeepSeek API issue: {@code json_object} + multi-turn history returns
 * whitespace instead of JSON, regardless of thinking mode).
 *
 * <p>Thinking mode is disabled via {@code reasoning: {effort: "none"}}. The model still reasons via
 * the veto_pulse JSON {@code thought} field.
 *
 * <p>Tool-call history is rendered as veto_pulse JSON (the same format the model is asked to emit)
 * rather than native {@code tool_calls}/{@code tool} role messages. Rendering prior tool calls as
 * prose ("Calling view_file(...)") taught the model to answer in prose and broke {@code
 * text.format: json_schema} enforcement on multi-turn conversations; keeping the history format
 * uniform with the requested output format keeps the model in JSON mode.
 */
final class DeepSeekLlmClient extends LlmClient {

    private static final @NonNull HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final @NonNull String baseUrl;
    private final @NonNull String apiKey;
    private final @NonNull String providerName;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull CapabilityTranslator capabilityTranslator;

    DeepSeekLlmClient(
            @NonNull String baseUrl,
            @NonNull String apiKey,
            @NonNull String providerName,
            @NonNull ObjectMapper objectMapper,
            @NonNull CapabilityTranslator capabilityTranslator) {
        this.baseUrl = baseUrl.isEmpty() ? "https://api.deepseek.com" : baseUrl;
        this.apiKey = apiKey;
        this.providerName = providerName;
        this.objectMapper = objectMapper;
        this.capabilityTranslator = capabilityTranslator;
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        JsonNode configuredSchema = request.responseSchema();
        JsonNode responseSchema =
                configuredSchema != null
                        ? configuredSchema
                        : capabilityTranslator.vetoResponseSchema(false);

        try {
            // Build the text.format with json_schema for server-side schema enforcement.
            Map<String, Object> textFormat = new LinkedHashMap<>();
            textFormat.put("type", "json_schema");
            textFormat.put("name", "veto_pulse");
            textFormat.put(
                    "schema",
                    objectMapper.convertValue(
                            responseSchema, new TypeReference<Map<String, Object>>() {}));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", request.modelName());
            body.put("instructions", request.systemPrompt());
            body.put("reasoning", Map.of("effort", "none")); // disable thinking

            // Build input items from the conversation messages (skip system - it goes in
            // instructions).
            List<Map<String, Object>> inputItems = new java.util.ArrayList<>();
            for (ChatMessage msg : request.messages()) {
                if ("system".equals(msg.role())) {
                    continue;
                }
                inputItems.add(toInputItem(msg));
            }
            body.put("input", inputItems);
            body.put("text", Map.of("format", textFormat));

            LlmOptions options = request.options();
            Integer maxTokens = options.maxTokens();
            if (maxTokens != null) {
                body.put("max_output_tokens", maxTokens);
            }

            String json = objectMapper.writeValueAsString(body);
            org.slf4j.LoggerFactory.getLogger("top.focess.veto.llm.client.DeepSeekLlmClient")
                    .debug(
                            "DeepSeek Responses API request ({} chars): {}",
                            json.length(),
                            json.length() > 2000 ? json.substring(0, 2000) + "..." : json);
            HttpRequest httpRequest =
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/responses"))
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
                                + " Responses API returned HTTP "
                                + httpResponse.statusCode()
                                + ": "
                                + httpResponse.body());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap =
                    objectMapper.readValue(httpResponse.body(), Map.class);

            String content = extractResponsesContent(responseMap);

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) responseMap.get("usage");
            if (usage != null) {
                Number prompt = (Number) usage.get("input_tokens");
                Number completion = (Number) usage.get("output_tokens");
                if (prompt != null && completion != null) {
                    top.focess.veto.llm.core.LlmSystemUsage.set(
                            prompt.longValue(), completion.longValue());
                }
            }

            org.slf4j.LoggerFactory.getLogger("top.focess.veto.llm.client.DeepSeekLlmClient")
                    .debug(
                            "DeepSeek Responses API response: contentLen={} contentBlank={}",
                            content == null ? 0 : content.length(),
                            content == null || content.isBlank());

            if (content == null || content.isBlank()) {
                throw new ModelCapabilityException(
                        providerName + " Responses API returned blank content", true);
            }

            // The Responses API's json_schema enforcement is not 100% reliable - the model
            // sometimes prepends text or wraps JSON in markdown. Extract the JSON.
            content = extractJson(content);

            String summary = "model=" + request.modelName() + ", via=responses-api";
            return new RawCompletion(summary, content);
        } catch (ModelCapabilityException e) {
            throw e;
        } catch (InterruptedException e) {
            // Restore the flag for the caller, and say WHAT happened - InterruptedException carries
            // no message, so the generic wrap below would surface a useless "... failed: null".
            Thread.currentThread().interrupt();
            throw new ModelCapabilityException(
                    providerName
                            + " REST call interrupted (the agent thread was interrupted mid-request"
                            + " - a cancel, a shutdown, or external interference)",
                    e);
        } catch (Exception e) {
            throw new ModelCapabilityException(
                    providerName
                            + " REST call failed: "
                            + (e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName()),
                    e);
        }
    }

    /**
     * Extracts the text content from a DeepSeek Responses API response. The response may have
     * {@code output_text} (simple string) or an {@code output} array of items containing a message
     * with {@code output_text} content parts.
     */
    @SuppressWarnings("unchecked")
    private static String extractResponsesContent(@NonNull Map<String, Object> responseMap) {
        // Try output_text first (simple string field)
        Object outputText = responseMap.get("output_text");
        if (outputText instanceof String s && !s.isBlank()) {
            return s;
        }
        // Try the output array
        Object output = responseMap.get("output");
        if (output instanceof List<?> outputList) {
            for (Object item : outputList) {
                if (item instanceof Map<?, ?> itemMap) {
                    String type = (String) itemMap.get("type");
                    if ("message".equals(type)) {
                        Object contentArr = itemMap.get("content");
                        if (contentArr instanceof List<?> contentList) {
                            for (Object c : contentList) {
                                if (c instanceof Map<?, ?> contentMap) {
                                    if ("output_text".equals(contentMap.get("type"))) {
                                        Object text = contentMap.get("text");
                                        if (text instanceof String s) {
                                            return s;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if ("output_text".equals(type)) {
                        Object text = itemMap.get("text");
                        if (text instanceof String s) {
                            return s;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Converts a {@link ChatMessage} to a Responses API input item. Tool results are rendered as
     * user messages; tool-call assistant messages are rendered as the SAME veto_pulse JSON the
     * model is asked to emit (a {@code thought} + {@code calls} object). This keeps the
     * conversation history format uniform with the requested output format - rendering prior tool
     * calls as prose ("Calling view_file(...)") taught the model to answer in prose and broke
     * {@code text.format: json_schema} enforcement on multi-turn conversations.
     */
    private @NonNull Map<String, Object> toInputItem(@NonNull ChatMessage msg)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if ("tool".equals(msg.role())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "user");
            m.put("content", msg.content());
            return m;
        }
        if ("assistant".equals(msg.role())) {
            // Render EVERY assistant turn as veto_pulse JSON - both tool-call turns
            // (thought + calls) and message-only answer turns (message). Rendering an answer
            // turn as raw natural language taught the model to answer in prose on subsequent
            // turns (it mimicked the history format); keeping the entire history in veto_pulse
            // JSON keeps the model in JSON mode across the whole conversation.
            Map<String, Object> root = new LinkedHashMap<>();
            if (msg.callId() != null) {
                Map<String, Object> call = new LinkedHashMap<>();
                String toolName = msg.toolName();
                call.put("tool_name", toolName != null ? toolName : "");
                call.put("args", parseArgsObject(msg.toolArgs()));
                if (!msg.content().isEmpty()) {
                    root.put("thought", msg.content());
                }
                root.put("calls", List.of(call));
            } else {
                root.put("message", msg.content());
            }
            root.put("features", Map.of("guided", false));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "assistant");
            m.put("content", objectMapper.writeValueAsString(root));
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", msg.role());
        m.put("content", msg.content());
        return m;
    }

    /**
     * Parses a tool-args JSON string into a {@link JsonNode} for embedding in the reconstructed
     * veto_pulse history. Returns an empty object node when the args are null/blank/invalid so the
     * rendered call is always well-formed JSON.
     */
    private @NonNull JsonNode parseArgsObject(String toolArgs) {
        if (toolArgs == null || toolArgs.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(toolArgs);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Extracts the JSON object from a response that may have leading/trailing text or markdown code
     * blocks. The Responses API's json_schema enforcement is not 100% reliable.
     */
    // Package-private for DeepSeekLlmClientExtractJsonTest. The implementation lives on the
    // LlmClient base (shared with AnthropicLlmClient's tool_choice fallback).
    @NonNull String extractJson(@NonNull String content) {
        return extractJson(objectMapper, content);
    }
}
