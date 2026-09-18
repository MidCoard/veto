package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolDocumentation;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.*;
import top.focess.veto.llm.provider.AbstractLlmProvider;
import top.focess.veto.observability.AuditLogger;

class NativeReasoningWireTest {
    private static final @NonNull ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final @NonNull ToolDefinition TOOL =
            new ToolDefinition(
                    "read_file",
                    "Read",
                    Map.of("type", "object", "properties", Map.of()),
                    List.of(),
                    ToolDocumentation.empty(),
                    List.of(),
                    List.of());

    @Test
    void preservesCompatibleDefaultsForModelsWithoutReasoningControls() {
        assertTrue(
                ModelReasoning.anthropic(request(ProviderType.ANTHROPIC, "MiniMax-M2.7", List.of()))
                        .isEmpty());
        assertTrue(
                ModelReasoning.anthropic(
                                request(
                                        ProviderType.ANTHROPIC,
                                        "claude-3-5-sonnet-latest",
                                        List.of()))
                        .isEmpty());
        assertFalse(ModelReasoning.openAi("gpt-4o"));
        assertFalse(ModelReasoning.openAi("gpt-5-chat-latest"));
        assertFalse(ModelReasoning.openAi("o1-mini"));
        assertFalse(ModelReasoning.openAi("o1-preview"));
        assertFalse(ModelReasoning.gemini("gemini-2.0-flash"));
        assertTrue(ModelReasoning.openAi("o3"));
        assertTrue(ModelReasoning.openAi("gpt-5.4"));
        assertEquals(
                2048,
                ModelReasoning.anthropic(
                                request(ProviderType.ANTHROPIC, "claude-sonnet-4-5", List.of()))
                        .get("budget_tokens"));
    }

    @ParameterizedTest
    @CsvSource({
        "ANTHROPIC,MiniMax-M3",
        "ANTHROPIC,claude-sonnet-4-6",
        "ANTHROPIC,claude-sonnet-4-5",
        "GEMINI,gemini-2.5-flash",
        "GEMINI,gemini-3-flash-preview",
        "DEEPSEEK,deepseek-flash",
        "OPENAI,gpt-5"
    })
    void enablesReasoningAndReplaysNativeStateAfterDurableHistory(
            @NonNull ProviderType type, @NonNull String model) throws Exception {
        var captured = new AtomicReference<String>();
        String response = wire(type);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    captured.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var translator = new VetoCapabilityTranslator();
        LlmClient client =
                switch (type) {
                    case ANTHROPIC ->
                            new AnthropicLlmClient(
                                    AnthropicOkHttpClient.builder()
                                            .baseUrl(url)
                                            .apiKey("local-invalid")
                                            .build(),
                                    MAPPER);
                    case GEMINI ->
                            new GeminiLlmClient(
                                    Client.builder()
                                            .apiKey("local-invalid")
                                            .vertexAI(false)
                                            .httpOptions(
                                                    HttpOptions.builder()
                                                            .baseUrl(url)
                                                            .apiVersion("v1beta")
                                                            .build())
                                            .build(),
                                    MAPPER,
                                    translator);
                    case DEEPSEEK ->
                            new DeepSeekLlmClient(url, "local-invalid", "test", MAPPER, translator);
                    case OPENAI ->
                            new OpenAiLlmClient(
                                    OpenAIOkHttpClient.builder()
                                            .baseUrl(url)
                                            .apiKey("local-invalid")
                                            .build(),
                                    true,
                                    "test",
                                    MAPPER,
                                    translator);
                };
        @NonNull AuditLogger audit = mock();
        var provider =
                new AbstractLlmProvider(MAPPER, audit) {
                    @Override
                    public boolean supports(@NonNull ProviderType candidate) {
                        return candidate == type;
                    }

                    @Override
                    public String defaultBaseUrl() {
                        return url;
                    }

                    @Override
                    protected @NonNull String providerName() {
                        return "test";
                    }

                    @Override
                    protected LlmClient.@NonNull RawCompletion invoke(
                            @NonNull ResolvedRequest request) throws Exception {
                        return client.complete(request);
                    }
                };
        try {
            var first = request(type, model, List.of(ChatMessage.user("Read")));
            var result = provider.execute(new ResolvedRequest(first, url, "local-invalid"));
            String sent = captured.get();
            assertNotNull(sent);
            var body = MAPPER.readTree(sent);
            assertTrue(sent.contains("Reasoning and verification"));
            assertFalse(sent.contains("\"name\":\"think\""));
            switch (type) {
                case ANTHROPIC -> {
                    assertEquals(
                            model.endsWith("4-5") ? "enabled" : "adaptive",
                            body.path("thinking").path("type").asText());
                    if (model.startsWith("claude-")) assertFalse(body.has("temperature"));
                }
                case GEMINI -> {
                    var config = body.path("generationConfig");
                    assertTrue(config.path("thinkingConfig").path("includeThoughts").asBoolean());
                    if (model.startsWith("gemini-3")) {
                        assertEquals(
                                "HIGH",
                                config.path("thinkingConfig").path("thinkingLevel").asText());
                        assertFalse(config.has("temperature"));
                    } else
                        assertEquals(
                                -1, config.path("thinkingConfig").path("thinkingBudget").asInt());
                }
                case DEEPSEEK ->
                        assertEquals("high", body.path("reasoning").path("effort").asText());
                case OPENAI -> {
                    assertEquals("medium", body.path("reasoning_effort").asText());
                    assertFalse(body.has("temperature"));
                    assertFalse(body.has("top_p"));
                    assertNull(result.thought());
                    return;
                }
            }
            String thought = result.thought();
            if (thought == null) throw new AssertionError("Expected provider reasoning");
            assertEquals("Check the evidence.", thought);
            assertEquals("Reading.", result.message());
            var calls = result.calls();
            if (calls == null) throw new AssertionError("Expected native calls");
            assertEquals(2, calls.size());
            List<TurnRecord> history = new ArrayList<>();
            history.add(TurnRecord.userPrompt(1, "Read"));
            history.add(
                    new TurnRecord(
                            2,
                            TurnType.ASSISTANT_THOUGHT,
                            Map.of(
                                    "response",
                                    thought,
                                    "provider_reasoning",
                                    true,
                                    "response_format",
                                    "text",
                                    "model_call_id",
                                    "request-1"),
                            null));
            history.add(
                    new TurnRecord(
                            3,
                            TurnType.ASSISTANT_RESPONSE,
                            Map.of(
                                    "content",
                                    "Reading.",
                                    "model_call_id",
                                    "request-1",
                                    "native_response_text",
                                    true),
                            null));
            int number = 4;
            for (var call : calls) {
                var turn = TurnRecord.toolCall(number++, call);
                var payload = new LinkedHashMap<>(turn.payload());
                payload.put("model_call_id", "request-1");
                history.add(
                        MAPPER.readValue(
                                MAPPER.writeValueAsString(
                                        new TurnRecord(
                                                turn.turnNumber(), turn.type(), payload, null)),
                                ToolDocs.nonNullClass(TurnRecord.class)));
                history.add(TurnRecord.toolResponse(number++, call.callId(), "result", true));
            }
            var compiler = PromptCompiler.isolated(translator, MAPPER, "System", 100000);
            List<ChatMessage> compiled =
                    ReflectionTestUtils.invokeMethod(
                            compiler, "resolveRewinds", history, ToolResultPresentationMode.BASIC);
            if (compiled == null) throw new AssertionError("Expected compiled history");
            assertTrue(
                    compiled.stream()
                            .noneMatch(
                                    message -> message.content().contains("Check the evidence.")));
            provider.execute(
                    new ResolvedRequest(request(type, model, compiled), url, "local-invalid"));
            String second = captured.get();
            assertNotNull(second);
            assertEquals(1, second.split("Check the evidence\\.", -1).length - 1);
            assertEquals(1, second.split("Reading\\.", -1).length - 1);
            var replay = MAPPER.readTree(second);
            switch (type) {
                case ANTHROPIC -> {
                    var blocks = replay.path("messages").path(1).path("content");
                    assertEquals("thinking", blocks.path(0).path("type").asText());
                    assertEquals("signed-state", blocks.path(0).path("signature").asText());
                    assertEquals(4, blocks.size());
                    assertEquals(2, replay.path("messages").path(2).path("content").size());
                }
                case GEMINI ->
                        assertEquals(
                                "c2lnbmVk",
                                replay.path("contents")
                                        .path(1)
                                        .path("parts")
                                        .path(0)
                                        .path("thoughtSignature")
                                        .asText());
                case DEEPSEEK ->
                        assertEquals(
                                "reasoning", replay.path("input").path(1).path("type").asText());
                default -> throw new AssertionError();
            }
        } finally {
            server.stop(0);
            LlmSystemUsage.drain();
        }
    }

    private static @NonNull VetoRequest request(
            @NonNull ProviderType type, @NonNull String model, @NonNull List<ChatMessage> history) {
        return new VetoRequest(
                "System",
                "Read",
                List.of(TOOL),
                type,
                model,
                "key",
                new LlmOptions(0.5, 0.9, 4096, null),
                history,
                null,
                null);
    }

    private static @NonNull String wire(@NonNull ProviderType type) {
        return switch (type) {
            case ANTHROPIC ->
                    """
                {"id":"r","type":"message","role":"assistant","model":"test","stop_reason":"tool_use",
                 "usage":{"input_tokens":10,"output_tokens":20},"content":[
                 {"type":"thinking","thinking":"Check the evidence.","signature":"signed-state"},
                 {"type":"text","text":"Reading."},
                 {"type":"tool_use","id":"n1","name":"read_file","input":{}},
                 {"type":"tool_use","id":"n2","name":"read_file","input":{}}]}
                """;
            case GEMINI ->
                    """
                {"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[
                 {"text":"Check the evidence.","thought":true,"thoughtSignature":"c2lnbmVk"},
                 {"text":"Reading."},
                 {"functionCall":{"id":"n1","name":"read_file","args":{}}},
                 {"functionCall":{"id":"n2","name":"read_file","args":{}}}]}}]}
                """;
            case DEEPSEEK ->
                    """
                {"output":[{"type":"reasoning","id":"rs1","summary":[],"content":[{"type":"reasoning_text","text":"Check the evidence."}]},
                 {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Reading."}]},
                 {"type":"function_call","call_id":"n1","name":"read_file","arguments":"{}"},
                 {"type":"function_call","call_id":"n2","name":"read_file","arguments":"{}"}]}
                """;
            case OPENAI ->
                    """
                {"id":"r","object":"chat.completion","created":0,"model":"gpt-5","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Answer."}}]}
                """;
        };
    }
}
