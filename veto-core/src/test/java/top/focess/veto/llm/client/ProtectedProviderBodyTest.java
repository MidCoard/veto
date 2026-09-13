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
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.LlmSystemUsage;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.vault.SecretCandidateStore;

/** Captures actual SDK HTTP bytes at a local provider substitute, never real credentials. */
class ProtectedProviderBodyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedUserAndFileValuesStayAbsentAfterSdkSerialization(boolean guided) throws Exception {
        String userValue = "ghp_SYNTHETIC0913INVALIDUSER00000000000000000";
        String fileValue = "ghp_SYNTHETIC0913INVALIDFILE00000000000000000";
        var store = new SecretCandidateStore();
        var scope = new SecretCandidateStore.Scope("test-owner", "test-session", "test-agent");
        String user = store.capture(scope, "browser-substitute", "token=" + userValue).text();
        String file = store.captureFile(scope, "file-substitute", "token=" + fileValue).text();
        var source =
                PromptSource.compile(
                        "wire-test.mdc",
                        "---\nversion: 1\nid: wire\nrequires: [VALUE, guided]\n---\n{{VALUE}}\n@if guided\nGuided\n@endif",
                        Map.of("VALUE", "System {{literal-data}}"),
                        Map.of("guided", guided),
                        Map.of());
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/messages",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            ("{\"id\":\"test-message\",\"type\":\"message\",\"role\":\"assistant\","
                                            + "\"model\":\"test-model\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                                            + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                                            + "\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        var sdk =
                AnthropicOkHttpClient.builder()
                        .apiKey("invalid-local-test-auth")
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build();
        try {
            var request =
                    new VetoRequest(
                            source.text(),
                            user,
                            List.of(),
                            ProviderType.ANTHROPIC,
                            "test-model",
                            "test-key-reference",
                            LlmOptions.defaults(),
                            List.of(
                                    ChatMessage.user(user),
                                    ChatMessage.assistantToolCall(
                                            "read", "view_file", "{}", "", null),
                                    ChatMessage.toolResult("read", file)),
                            new VetoCapabilityTranslator().vetoResponseSchema(guided, List.of()),
                            null);
            var client = new AnthropicLlmClient(sdk, new ObjectMapper());
            // Repeat the same protected history as a resend/recovery boundary check.
            for (int attempt = 0; attempt < 2; attempt++)
                client.complete(new ResolvedRequest(request, null, "invalid-local-test-auth"));
            assertEquals(2, bodies.size());
            for (String body : bodies) {
                var system = new ObjectMapper().readTree(body).path("system");
                String text =
                        system.isTextual() ? system.asText() : system.path(0).path("text").asText();
                assertTrue(text.startsWith(source.text()));
                for (var span : source.sources())
                    assertEquals(
                            source.text().substring(span.start(), span.end()),
                            text.substring(span.start(), span.end()));
                assertFalse(
                        body.contains(userValue), "Synthetic user value crossed the HTTP boundary");
                assertFalse(
                        body.contains(fileValue), "Synthetic file value crossed the HTTP boundary");
                assertTrue(body.contains("SECRET_REF:"));
                assertTrue(body.contains(user));
                assertTrue(body.contains(file));
                assertEquals(guided, body.contains("\"tool_choice\""));
            }
        } finally {
            server.stop(0);
            LlmSystemUsage.drain();
        }
    }
}
