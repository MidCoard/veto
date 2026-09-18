package top.focess.veto.llm.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.LlmSystemUsage;
import top.focess.veto.llm.core.NativeToolState;
import top.focess.veto.llm.core.ProviderMessages;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/** DeepSeek Responses API adapter with native reasoning, functions and matching tool results. */
final class DeepSeekLlmClient extends LlmClient {

    private static final @NonNull HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final @NonNull String baseUrl;
    private final @NonNull String apiKey;
    private final @NonNull String providerName;
    private final @NonNull ObjectMapper objectMapper;

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
    }

    @Override
    public @NonNull RawCompletion complete(@NonNull ResolvedRequest resolved) {
        VetoRequest request = resolved.request();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", request.modelName());
            body.put("instructions", NativeToolResponses.prompt(request));
            if (NativeToolResponses.enabled(request)) {
                body.put(
                        "tools",
                        request.tools().stream()
                                .map(
                                        tool ->
                                                Map.of(
                                                        "type",
                                                        "function",
                                                        "name",
                                                        tool.name(),
                                                        "description",
                                                        tool.description(),
                                                        "parameters",
                                                        tool.inputSchema(),
                                                        "strict",
                                                        false))
                                .toList());
                body.put("tool_choice", "auto");
            }
            body.put("reasoning", Map.of("effort", "high"));

            // Build input items from the conversation messages (skip system - it goes in
            // instructions).
            List<Map<String, Object>> inputItems = new ArrayList<>();
            for (var group : ProviderMessages.groups(request)) {
                var headState = group.getFirst().nativeState();
                boolean replayNative = headState != null && headState.position() == 0;
                for (ChatMessage msg : group) {
                    var state = msg.nativeState();
                    if (replayNative
                            && state != null
                            && state.supports("DEEPSEEK", request.modelName())) {
                        List<Map<String, Object>> original =
                                objectMapper.readValue(
                                        state.partsJson(),
                                        new TypeReference<List<Map<String, Object>>>() {});
                        inputItems.addAll(original);
                    } else inputItems.add(toInputItem(msg));
                }
            }
            if (inputItems.isEmpty())
                inputItems.add(Map.of("role", "user", "content", request.userPrompt()));
            body.put("input", inputItems);

            LlmOptions options = request.options();
            Integer maxTokens = options.maxTokens();
            if (maxTokens != null) {
                body.put("max_output_tokens", maxTokens);
            }

            Double temperature = options.temperature();
            if (temperature != null) {
                body.put("temperature", temperature);
            }

            String json = objectMapper.writeValueAsString(body);
            LoggerFactory.getLogger("top.focess.veto.llm.client.DeepSeekLlmClient")
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

            if ("incomplete".equals(responseMap.get("status"))
                    || "failed".equals(responseMap.get("status")))
                throw new ModelSchemaException(
                        "DeepSeek returned an incomplete response; no calls were executed");
            String content = extractResponsesContent(responseMap);
            var nativeCalls = new ArrayList<NativeToolResponses.Call>();
            Object output = responseMap.get("output");
            if (output instanceof List<?> items)
                for (Object item : items) {
                    if (item instanceof Map<?, ?> unsupported
                            && "custom_tool_call".equals(unsupported.get("type")))
                        throw new ModelSchemaException("Unsupported native custom tool call");
                    if (item instanceof Map<?, ?> call
                            && "function_call".equals(call.get("type"))) {
                        if (!(call.get("name") instanceof String name)
                                || !(call.get("arguments") instanceof String arguments)
                                || !(call.get("call_id") instanceof String id)
                                || id.isBlank())
                            throw new ModelSchemaException("Incomplete native function call");
                        nativeCalls.add(
                                new NativeToolResponses.Call(
                                        name,
                                        NativeToolResponses.arguments(objectMapper, arguments),
                                        id));
                    }
                }
            content = NativeToolResponses.normalize(objectMapper, request, content, nativeCalls);

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) responseMap.get("usage");
            if (usage != null) {
                Number prompt = (Number) usage.get("input_tokens");
                Number completion = (Number) usage.get("output_tokens");
                if (prompt != null && completion != null) {
                    Object details = usage.get("input_tokens_details");
                    Long cached =
                            details instanceof Map<?, ?> breakdown
                                            && breakdown.get("cached_tokens")
                                                    instanceof Number value
                                    ? value.longValue()
                                    : null;
                    LlmSystemUsage.set(prompt.longValue(), completion.longValue(), cached, null);
                }
            }

            LoggerFactory.getLogger("top.focess.veto.llm.client.DeepSeekLlmClient")
                    .debug(
                            "DeepSeek Responses API response: contentLen={} contentBlank={}",
                            content.length(),
                            content.isBlank());

            if (content.isBlank() && nativeCalls.isEmpty()) {
                throw new ModelCapabilityException(
                        providerName + " Responses API returned blank content", true);
            }

            String summary = "model=" + request.modelName() + ", via=responses-api";
            return NativeToolResponses.completion(
                            objectMapper,
                            summary,
                            content,
                            nativeCalls,
                            nativeStates(output, request.modelName()))
                    .withReasoning(extractReasoning(output));
        } catch (ModelCapabilityException | ModelSchemaException e) {
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

    private @NonNull List<NativeToolState> nativeStates(Object output, @NonNull String model)
            throws JsonProcessingException {
        var segments = new ArrayList<List<Object>>();
        var pending = new ArrayList<Object>();
        if (output instanceof List<?> items)
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map)) continue;
                pending.add(item);
                if ("function_call".equals(map.get("type"))) {
                    segments.add(new ArrayList<>(pending));
                    pending.clear();
                }
            }
        if (!segments.isEmpty()) segments.getLast().addAll(pending);
        String batch = UUID.randomUUID().toString();
        var states = new ArrayList<NativeToolState>();
        for (var segment : segments)
            states.add(
                    new NativeToolState(
                            "DEEPSEEK",
                            1,
                            model,
                            batch,
                            objectMapper.writeValueAsString(segment),
                            states.size()));
        return states;
    }

    private static @NonNull String extractReasoning(Object output) {
        var text = new ArrayList<String>();
        if (output instanceof List<?> items)
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map) || !"reasoning".equals(map.get("type")))
                    continue;
                Object blocks = map.get("content");
                if (!(blocks instanceof List<?> content) || content.isEmpty())
                    blocks = map.get("summary");
                if (blocks instanceof List<?> content)
                    for (Object block : content) {
                        if (block instanceof Map<?, ?> part
                                && part.get("text") instanceof String value) text.add(value);
                    }
            }
        return String.join("\n", text);
    }

    /**
     * Extracts the text content from a DeepSeek Responses API response. The response may have
     * {@code output_text} (simple string) or an {@code output} array of items containing a message
     * with {@code output_text} content parts.
     */
    private static @NonNull String extractResponsesContent(
            @NonNull Map<String, Object> responseMap) {
        var parts = new ArrayList<String>();
        Object output = responseMap.get("output");
        if (output instanceof List<?> items)
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map)) continue;
                if ("output_text".equals(map.get("type")) && map.get("text") instanceof String text)
                    parts.add(text);
                if ("message".equals(map.get("type"))
                        && map.get("content") instanceof List<?> content)
                    for (Object block : content)
                        if (block instanceof Map<?, ?> part
                                && "output_text".equals(part.get("type"))
                                && part.get("text") instanceof String text) parts.add(text);
            }
        if (responseMap.get("output_text") instanceof String text
                && !text.isBlank()
                && !text.equals(String.join("\n", parts))) parts.add(text);
        return String.join("\n", parts);
    }

    /** Replays calls and results as native Responses API items, preserving their pairing. */
    private @NonNull Map<String, Object> toInputItem(@NonNull ChatMessage msg)
            throws JsonProcessingException {
        String callId = msg.callId();
        if ("tool".equals(msg.role()) && callId != null) {
            return Map.of(
                    "type",
                    "function_call_output",
                    "call_id",
                    callId,
                    "output",
                    msg.toolResultContentWithStatus());
        }
        if ("assistant".equals(msg.role()) && callId != null) {
            String name = msg.toolName();
            String args = msg.toolArgs();
            return Map.of(
                    "type",
                    "function_call",
                    "call_id",
                    callId,
                    "name",
                    name == null ? "" : name,
                    "arguments",
                    args == null ? "{}" : args);
        }
        return Map.of(
                "role", "tool".equals(msg.role()) ? "user" : msg.role(), "content", msg.content());
    }
}
