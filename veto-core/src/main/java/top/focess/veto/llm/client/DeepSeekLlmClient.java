package top.focess.veto.llm.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.LlmSystemUsage;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/**
 * DeepSeek Responses API adapter: native functions with JSON response/guide compatibility. Thinking
 * remains disabled; function calls and outputs are replayed with matching call ids.
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
        try {
            List<Function> tools =
                    NativeToolResponses.enabled(request)
                            ? request.tools().stream()
                                    .map(
                                            tool ->
                                                    new Function(
                                                            "function",
                                                            tool.name(),
                                                            tool.description(),
                                                            tool.inputSchema(),
                                                            false))
                                    .toList()
                            : null;
            List<InputItem> inputItems = new ArrayList<>();
            for (ChatMessage msg : request.messages()) {
                if (!"system".equals(msg.role())) inputItems.add(toInputItem(msg));
            }
            if (inputItems.isEmpty())
                inputItems.add(new MessageInput("user", request.userPrompt()));
            LlmOptions options = request.options();
            var body =
                    new ResponsesRequest(
                            request.modelName(),
                            NativeToolResponses.prompt(request),
                            tools,
                            tools == null ? null : "auto",
                            new Reasoning("none"),
                            inputItems,
                            options.maxTokens(),
                            options.temperature());
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

            JsonNode response = objectMapper.readTree(httpResponse.body());
            if (response == null || !response.isObject())
                throw new ModelSchemaException("Invalid Responses API envelope");
            String status = response.path("status").asText();
            if ("incomplete".equals(status) || "failed".equals(status))
                throw new ModelSchemaException(
                        "DeepSeek returned an incomplete response; no calls were executed");
            String content = extractResponsesContent(response);
            var nativeCalls = new ArrayList<NativeToolResponses.Call>();
            for (JsonNode item : response.path("output")) {
                if ("custom_tool_call".equals(item.path("type").asText()))
                    throw new ModelSchemaException("Unsupported native custom tool call");
                if ("function_call".equals(item.path("type").asText())) {
                    if (!item.path("name").isTextual()
                            || !item.path("arguments").isTextual()
                            || !item.path("call_id").isTextual()
                            || item.path("call_id").asText().isBlank())
                        throw new ModelSchemaException("Incomplete native function call");
                    nativeCalls.add(
                            new NativeToolResponses.Call(
                                    item.path("name").asText(),
                                    NativeToolResponses.arguments(
                                            objectMapper, item.path("arguments").asText()),
                                    item.path("call_id").asText()));
                }
            }
            content =
                    NativeToolResponses.normalize(
                            objectMapper, request, content == null ? "" : content, nativeCalls);

            JsonNode usage = response.path("usage");
            if (usage.path("input_tokens").isNumber() && usage.path("output_tokens").isNumber()) {
                JsonNode cached = usage.path("input_tokens_details").path("cached_tokens");
                LlmSystemUsage.set(
                        usage.path("input_tokens").longValue(),
                        usage.path("output_tokens").longValue(),
                        cached.isNumber() ? cached.longValue() : null,
                        null);
            }

            LoggerFactory.getLogger("top.focess.veto.llm.client.DeepSeekLlmClient")
                    .debug(
                            "DeepSeek Responses API response: contentLen={} contentBlank={}",
                            content == null ? 0 : content.length(),
                            content == null || content.isBlank());

            if ((content == null || content.isBlank()) && nativeCalls.isEmpty()) {
                throw new ModelCapabilityException(
                        providerName + " Responses API returned blank content", true);
            }

            String summary = "model=" + request.modelName() + ", via=responses-api";
            return NativeToolResponses.completion(
                    objectMapper, summary, content, nativeCalls, java.util.List.of());
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

    /**
     * Extracts the text content from a DeepSeek Responses API response. The response may have
     * {@code output_text} (simple string) or an {@code output} array of items containing a message
     * with {@code output_text} content parts.
     */
    private static @NonNull String extractResponsesContent(@NonNull JsonNode response) {
        var parts = new ArrayList<String>();
        for (JsonNode item : response.path("output")) {
            if ("output_text".equals(item.path("type").asText()) && item.path("text").isTextual())
                parts.add(item.path("text").asText());
            if ("message".equals(item.path("type").asText()))
                for (JsonNode block : item.path("content"))
                    if ("output_text".equals(block.path("type").asText())
                            && block.path("text").isTextual())
                        parts.add(block.path("text").asText());
        }
        String text = response.path("output_text").asText("");
        if (!text.isBlank() && !text.equals(String.join("\n", parts))) parts.add(text);
        return String.join("\n", parts);
    }

    private sealed interface InputItem permits MessageInput, FunctionInput, FunctionOutput {}

    private record MessageInput(@NonNull String role, @NonNull String content)
            implements InputItem {}

    private record FunctionInput(
            @NonNull String type,
            @JsonProperty("call_id") @NonNull String callId,
            @NonNull String name,
            @NonNull String arguments)
            implements InputItem {}

    private record FunctionOutput(
            @NonNull String type,
            @JsonProperty("call_id") @NonNull String callId,
            @NonNull String output)
            implements InputItem {}

    private record Function(
            @NonNull String type,
            @NonNull String name,
            @NonNull String description,
            @NonNull Map<String, Object> parameters,
            boolean strict) {}

    private record Reasoning(@NonNull String effort) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ResponsesRequest(
            @NonNull String model,
            @NonNull String instructions,
            @Nullable List<Function> tools,
            @JsonProperty("tool_choice") @Nullable String toolChoice,
            @NonNull Reasoning reasoning,
            @NonNull List<InputItem> input,
            @JsonProperty("max_output_tokens") @Nullable Integer maxOutputTokens,
            @Nullable Double temperature) {}

    /** Replays calls and results as native Responses API items, preserving their pairing. */
    private @NonNull InputItem toInputItem(@NonNull ChatMessage msg) {
        String callId = msg.callId();
        if ("tool".equals(msg.role()) && callId != null)
            return new FunctionOutput(
                    "function_call_output", callId, msg.toolResultContentWithStatus());
        if ("assistant".equals(msg.role()) && callId != null) {
            String name = msg.toolName();
            String args = msg.toolArgs();
            return new FunctionInput(
                    "function_call", callId, name == null ? "" : name, args == null ? "{}" : args);
        }
        return new MessageInput("tool".equals(msg.role()) ? "user" : msg.role(), msg.content());
    }
}
