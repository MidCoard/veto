package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolDocumentation;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.*;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/** Actual SDK/REST HTTP tests against local substitutes; no model credentials are used. */
class NativeProvidersWireTest {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();
    private static final @NonNull ToolDefinition TOOL =
            new ToolDefinition(
                    "read_file",
                    "Read",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("path", Map.of("type", "string")),
                            "required",
                            List.of("path")),
                    List.of(),
                    ToolDocumentation.empty(),
                    List.of(),
                    List.of());

    @ParameterizedTest
    @EnumSource(
            value = ProviderType.class,
            names = {"OPENAI", "DEEPSEEK", "GEMINI"})
    void nativeAndJsonChannelsAndMultiTurnHistory(@NonNull ProviderType type) throws Exception {
        var captured = new AtomicReference<String>();
        var reply = new AtomicReference<String>(wire(type, "", true));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    captured.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var translator = new VetoCapabilityTranslator();
        LlmClient client =
                switch (type) {
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
                    case DEEPSEEK ->
                            new DeepSeekLlmClient(url, "local-invalid", "test", MAPPER, translator);
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
                    default -> throw new AssertionError();
                };
        try {
            for (boolean guided : List.of(false, true)) {
                var request = request(type, guided, List.of(ChatMessage.user("Read")));
                reply.set(wire(type, "", true));
                var raw = client.complete(new ResolvedRequest(request, url, "local-invalid"));
                var response =
                        MAPPER.readValue(
                                raw.rawResponse(), ToolDocs.nonNullClass(VetoResponse.class));
                var calls = response.calls();
                if (calls == null) throw new AssertionError("Expected calls");
                assertEquals(2, calls.size());
                assertEquals("/中文 notes", calls.getFirst().args().get("path"));
                String sent = captured.get();
                assertNotNull(sent);
                var body = MAPPER.readTree(sent);
                assertTrue(sent.contains("Use native tool calls"));
                assertFalse(sent.contains("This turn does not enable native tools"));
                assertTrue(body.has("tools"));
                if (type == ProviderType.GEMINI) {
                    assertEquals(
                            "AUTO",
                            body.path("toolConfig")
                                    .path("functionCallingConfig")
                                    .path("mode")
                                    .asText());
                    assertFalse(body.path("generationConfig").has("responseMimeType"));
                } else assertEquals("auto", body.path("tool_choice").asText());
                var history = new ArrayList<ChatMessage>();
                history.add(ChatMessage.user("Read"));
                for (int i = 0; i < calls.size(); i++) {
                    var call = calls.get(i);
                    var message =
                            ChatMessage.assistantToolCall(
                                    call.callId(),
                                    call.toolName(),
                                    MAPPER.writeValueAsString(call.args()),
                                    "",
                                    null);
                    if (type == ProviderType.GEMINI) {
                        // Persist metadata as the runtime does, then reconstruct after a DB JSON
                        // round-trip.
                        var persisted =
                                TurnRecord.toolCall(
                                        i + 1, call.withNativeState(raw.nativeStates().get(i)));
                        var payload =
                                MAPPER.readTree(MAPPER.writeValueAsString(persisted.payload()));
                        @SuppressWarnings("unchecked")
                        Map<String, Object> restored = MAPPER.convertValue(payload, Map.class);
                        message =
                                message.withNativeState(
                                        NativeToolState.fromPayload(restored.get("native_state")));
                    }
                    history.add(message);
                    history.add(ChatMessage.toolResult(call.callId(), "result-" + i, i == 0));
                }
                reply.set(wire(type, "{\"message\":\"Done\"}", false));
                var followup = request(type, guided, history);
                assertEquals(
                        "Done",
                        MAPPER.readTree(
                                        client.complete(
                                                        new ResolvedRequest(
                                                                followup, url, "local-invalid"))
                                                .rawResponse())
                                .path("message")
                                .asText());
                String second = captured.get();
                assertNotNull(second);
                assertTrue(second.contains("result-1"));
                var secondBody = MAPPER.readTree(second);
                if (type == ProviderType.GEMINI) {
                    var contents = secondBody.path("contents");
                    assertEquals(ProviderMessages.groups(followup).size(), contents.size());
                    assertEquals(3, contents.size());
                    assertEquals(2, contents.path(1).path("parts").size());
                    assertEquals(
                            "c2lnbmF0dXJl",
                            contents.path(1)
                                    .path("parts")
                                    .path(0)
                                    .path("thoughtSignature")
                                    .asText());
                    assertEquals(
                            "n1",
                            contents.path(2)
                                    .path("parts")
                                    .path(0)
                                    .path("functionResponse")
                                    .path("id")
                                    .asText());
                    assertFalse(
                            contents.path(2)
                                    .path("parts")
                                    .path(1)
                                    .path("functionResponse")
                                    .path("response")
                                    .path("success")
                                    .asBoolean());
                } else if (type == ProviderType.DEEPSEEK) {
                    assertEquals(
                            "function_call",
                            secondBody.path("input").path(1).path("type").asText());
                    assertEquals(
                            secondBody.path("input").path(1).path("call_id"),
                            secondBody.path("input").path(2).path("call_id"));
                } else {
                    assertEquals(
                            secondBody
                                    .path("messages")
                                    .path(2)
                                    .path("tool_calls")
                                    .path(0)
                                    .path("id"),
                            secondBody.path("messages").path(3).path("tool_call_id"));
                }
                if (type == ProviderType.GEMINI) {
                    var compacted =
                            request(
                                    type,
                                    guided,
                                    List.of(
                                            ChatMessage.user("Continue"),
                                            history.get(3),
                                            history.get(4)));
                    client.complete(new ResolvedRequest(compacted, url, "local-invalid"));
                    String tail = captured.get();
                    assertNotNull(tail);
                    String tailContents = MAPPER.readTree(tail).path("contents").toString();
                    assertFalse(tailContents.contains("thoughtSignature"));
                    assertFalse(tailContents.contains("functionCall"));
                    assertTrue(tail.contains("read_file"));
                }
                if (guided) {
                    String guide =
                            "{\"guide\":{\"actions\":[{\"id\":\"stop\",\"type\":\"STOP\",\"label\":\"Done\"}]}}";
                    reply.set(wire(type, guide, false));
                    assertEquals(
                            guide,
                            client.complete(new ResolvedRequest(request, url, "local-invalid"))
                                    .rawResponse());
                }
                // Same and different JSON operations, plus guide: all are rejected before
                // execution.
                for (String mixed :
                        List.of(
                                "{\"calls\":[{\"tool_name\":\"read_file\",\"args\":{\"path\":\"/中文 notes\"}}]}",
                                "{\"calls\":[{\"tool_name\":\"read_file\",\"args\":{\"path\":\"different\"}}]}",
                                "{\"guide\":{\"actions\":[]}}")) {
                    reply.set(wire(type, mixed, true));
                    assertThrows(
                            ToolDocs.nonNullClass(ModelSchemaException.class),
                            () ->
                                    client.complete(
                                            new ResolvedRequest(request, url, "local-invalid")));
                }
                String jsonCall =
                        "{\"calls\":[{\"tool_name\":\"read_file\",\"args\":{\"path\":\"compat\"}}]}";
                reply.set(wire(type, jsonCall, false));
                assertEquals(
                        jsonCall,
                        client.complete(new ResolvedRequest(request, url, "local-invalid"))
                                .rawResponse());
                var noTools =
                        new VetoRequest(
                                "System",
                                "Generate",
                                List.of(),
                                type,
                                "test",
                                "key",
                                LlmOptions.defaults(),
                                List.of(ChatMessage.user("Generate")),
                                MAPPER.readTree(
                                        "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}"),
                                null);
                reply.set(wire(type, "", true));
                assertThrows(
                        ToolDocs.nonNullClass(ModelSchemaException.class),
                        () -> client.complete(new ResolvedRequest(noTools, url, "local-invalid")));
                String disabled = captured.get();
                assertNotNull(disabled);
                assertFalse(MAPPER.readTree(disabled).has("tools"));
                assertTrue(disabled.contains("This turn does not enable native tools"));
                assertFalse(disabled.contains("Use native tool calls"));
            }
        } finally {
            server.stop(0);
            LlmSystemUsage.drain();
        }
    }

    private static @NonNull VetoRequest request(
            @NonNull ProviderType type, boolean guided, @NonNull List<ChatMessage> history) {
        return new VetoRequest(
                "System",
                "Read",
                List.of(TOOL),
                type,
                "test",
                "key",
                LlmOptions.defaults(),
                history,
                new VetoCapabilityTranslator().vetoResponseSchema(guided, List.of(TOOL)),
                null);
    }

    private static @NonNull String wire(
            @NonNull ProviderType type, @NonNull String text, boolean nativeCalls) {
        var root = MAPPER.createObjectNode();
        switch (type) {
            case OPENAI -> {
                root.put("id", "test");
                root.put("object", "chat.completion");
                root.put("created", 0);
                root.put("model", "test");
                var choice = root.putArray("choices").addObject();
                choice.put("index", 0);
                choice.put("finish_reason", nativeCalls ? "tool_calls" : "stop");
                var message = choice.putObject("message");
                message.put("role", "assistant");
                message.put("content", text);
                if (nativeCalls)
                    for (int i = 1; i <= 2; i++) {
                        var call = message.withArray("tool_calls").addObject();
                        call.put("id", "n" + i);
                        call.put("type", "function");
                        var function = call.putObject("function");
                        function.put("name", "read_file");
                        function.put("arguments", "{\"path\":\"/中文 notes\"}");
                    }
            }
            case DEEPSEEK -> {
                var output = root.putArray("output");
                if (nativeCalls)
                    for (int i = 1; i <= 2; i++) {
                        var call = output.addObject();
                        call.put("type", "function_call");
                        call.put("call_id", "n" + i);
                        call.put("name", "read_file");
                        call.put("arguments", "{\"path\":\"/中文 notes\"}");
                    }
                output.addObject()
                        .put("type", "message")
                        .putArray("content")
                        .addObject()
                        .put("type", "output_text")
                        .put("text", text);
            }
            case GEMINI -> {
                var candidate = root.putArray("candidates").addObject();
                candidate.put("finishReason", "STOP");
                var parts = candidate.putObject("content").put("role", "model").putArray("parts");
                if (!text.isEmpty()) parts.addObject().put("text", text);
                if (nativeCalls)
                    for (int i = 1; i <= 2; i++) {
                        var part = parts.addObject();
                        if (i == 1) part.put("thoughtSignature", "c2lnbmF0dXJl");
                        part.putObject("functionCall")
                                .put("name", "read_file")
                                .put("id", "n" + i)
                                .putObject("args")
                                .put("path", "/中文 notes");
                    }
            }
            default -> throw new AssertionError();
        }
        return root.toString();
    }
}
