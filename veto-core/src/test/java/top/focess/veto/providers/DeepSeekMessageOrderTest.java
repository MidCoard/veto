package top.focess.veto.providers;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderMessages;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.VetoRequest;

class DeepSeekMessageOrderTest {
    @Test
    void serializedToolExchangeAndCorrectionsUseTheCitationMessageBoundaries() throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/responses",
                exchange -> {
                    captured.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            "{\"output_text\":\"{\\\"message\\\":\\\"ok\\\"}\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                    exchange.close();
                });
        server.start();
        try {
            var mapper = new ObjectMapper();
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var messages =
                    List.of(
                            ChatMessage.system("System instructions"),
                            ChatMessage.user("Can I register the domain?"),
                            ChatMessage.assistantToolCall(
                                    "call",
                                    "web_fetch",
                                    "{\"url\":\"https://example.com\"}",
                                    "",
                                    null),
                            ChatMessage.toolResult(
                                    "call", "They are not available for registration or transfer."),
                            ChatMessage.user("Correct the citation."));
            var request =
                    new VetoRequest(
                            "System instructions",
                            "",
                            List.of(),
                            ProviderType.DEEPSEEK,
                            "test-model",
                            "test-key",
                            LlmOptions.defaults(),
                            messages,
                            mapper.createObjectNode(),
                            null);
            var client =
                    new DeepSeekLlmClient(
                            url, "test-secret", "DeepSeek", mapper, ProviderTestPrompts.PROMPTS);
            client.complete(new ResolvedRequest(request, url, "test-secret"));
            String sent = captured.get();
            if (sent == null) throw new AssertionError("No HTTP request received");
            var body = mapper.readTree(sent);
            var input = body.path("input");
            var groups = ProviderMessages.groups(request);
            assertEquals(groups.size(), input.size());
            assertEquals(4, input.size());
            assertTrue(body.path("instructions").asText().startsWith("System instructions"));
            assertEquals("user", input.get(0).path("role").asText());
            assertEquals("function_call", input.get(1).path("type").asText());
            assertEquals("web_fetch", input.get(1).path("name").asText());
            assertEquals("call", input.get(1).path("call_id").asText());
            assertEquals("function_call_output", input.get(2).path("type").asText());
            assertEquals("call", input.get(2).path("call_id").asText());
            assertEquals(
                    groups.get(2).getFirst().toolResultContentWithStatus(),
                    input.get(2).path("output").asText());
            assertEquals(groups.get(3).getFirst().content(), input.get(3).path("content").asText());
            assertFalse(sent.contains("sourceTurns"));
        } finally {
            server.stop(0);
        }
    }
}
