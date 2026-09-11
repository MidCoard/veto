package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderMessages;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;

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
                            url, "test-secret", "DeepSeek", mapper, new VetoCapabilityTranslator());
            client.complete(new ResolvedRequest(request, url, "test-secret"));
            String sent = captured.get();
            if (sent == null) throw new AssertionError("No HTTP request received");
            var body = mapper.readTree(sent);
            var input = body.path("input");
            var groups = ProviderMessages.groups(request);
            assertEquals(groups.size(), input.size());
            assertEquals(4, input.size());
            assertEquals("System instructions", body.path("instructions").asText());
            assertEquals("user", input.get(0).path("role").asText());
            assertEquals("assistant", input.get(1).path("role").asText());
            assertEquals(
                    "web_fetch",
                    mapper.readTree(input.get(1).path("content").asText())
                            .path("calls")
                            .get(0)
                            .path("tool_name")
                            .asText());
            assertEquals("user", input.get(2).path("role").asText());
            assertEquals(
                    groups.get(2).getFirst().toolResultContentWithStatus(),
                    input.get(2).path("content").asText());
            assertEquals(groups.get(3).getFirst().content(), input.get(3).path("content").asText());
            assertFalse(sent.contains("sourceTurns"));
        } finally {
            server.stop(0);
        }
    }
}
