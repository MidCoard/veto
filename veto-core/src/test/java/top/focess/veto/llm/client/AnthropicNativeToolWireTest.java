package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.tool.ToolDocumentation;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.*;

/** Verifies real SDK serialization and native response decoding without a remote model or key. */
class AnthropicNativeToolWireTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeCallAndItsResultRoundTripWithGuideEnabledOrDisabled(boolean guided)
            throws Exception {
        var mapper = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/messages",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    String content =
                            bodies.size() == 1
                                    ? "[{\"type\":\"tool_use\",\"id\":\"native-1\",\"name\":\"view_file\",\"input\":{\"absolutePath\":\"/workspace/中文 notes.txt\"}}]"
                                    : "[{\"type\":\"text\",\"text\":\"{\\\"message\\\":\\\"Read complete\\\"}\"}]";
                    byte[] response =
                            ("{\"id\":\"test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test\",\"content\":"
                                            + content
                                            + ",\"stop_reason\":\""
                                            + (bodies.size() == 1 ? "tool_use" : "end_turn")
                                            + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(response);
                    }
                });
        server.start();
        var sdk =
                AnthropicOkHttpClient.builder()
                        .apiKey("invalid-local-test-key")
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build();
        try {
            var tool =
                    new ToolDefinition(
                            "view_file",
                            "Read a file",
                            Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of("absolutePath", Map.of("type", "string")),
                                    "required",
                                    List.of("absolutePath")),
                            List.of(),
                            ToolDocumentation.empty(),
                            List.of(),
                            List.of());
            var schema = new VetoCapabilityTranslator().vetoResponseSchema(guided, List.of(tool));
            var client = new AnthropicLlmClient(sdk, mapper);
            var first =
                    new VetoRequest(
                            "System",
                            "Read",
                            List.of(tool),
                            ProviderType.ANTHROPIC,
                            "test",
                            "key-ref",
                            LlmOptions.defaults(),
                            List.of(ChatMessage.user("Read")),
                            schema,
                            null);
            var call =
                    mapper.readTree(
                                    client.complete(new ResolvedRequest(first, null, "unused"))
                                            .rawResponse())
                            .path("calls")
                            .path(0);
            assertEquals("view_file", call.path("tool_name").asText());
            assertEquals(
                    "/workspace/中文 notes.txt", call.path("args").path("absolutePath").asText());
            var second =
                    new VetoRequest(
                            "System",
                            "Read",
                            List.of(tool),
                            ProviderType.ANTHROPIC,
                            "test",
                            "key-ref",
                            LlmOptions.defaults(),
                            List.of(
                                    ChatMessage.user("Read"),
                                    ChatMessage.assistantToolCall(
                                            "runtime-1",
                                            "view_file",
                                            call.path("args").toString(),
                                            "",
                                            null),
                                    ChatMessage.toolResult("runtime-1", "file contents")),
                            schema,
                            null);
            assertEquals(
                    "Read complete",
                    mapper.readTree(
                                    client.complete(new ResolvedRequest(second, null, "unused"))
                                            .rawResponse())
                            .path("message")
                            .asText());
            assertEquals(2, bodies.size(), "A valid native response must not trigger a retry");
            for (String body : bodies) {
                var sent = mapper.readTree(body);
                assertEquals("auto", sent.path("tool_choice").path("type").asText());
                assertEquals("view_file", sent.path("tools").path(0).path("name").asText());
                assertTrue(sent.path("system").toString().contains("prefer native tool_use"));
            }
            var history = mapper.readTree(bodies.getLast()).path("messages");
            assertEquals("runtime-1", history.path(1).path("content").path(0).path("id").asText());
            assertEquals(
                    "runtime-1",
                    history.path(2).path("content").path(0).path("tool_use_id").asText());
            assertEquals(
                    "file contents",
                    history.path(2).path("content").path(0).path("content").asText());
        } finally {
            server.stop(0);
            LlmSystemUsage.drain();
        }
    }
}
