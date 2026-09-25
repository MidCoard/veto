package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.*;
import top.focess.veto.observability.AuditLogger;
import top.focess.veto.plugin.runtime.*;

class PluginLlmProvidersTest {
    @Test
    void discoveredProviderUsesHostPromptCompilerAndAudit() throws Exception {
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/responses",
                exchange -> {
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        var manager = PluginTestSupport.manager();
        try {
            @NonNull AuditLogger audit = mock();
            var providers = new PluginLlmProviders(manager, new ObjectMapper(), audit);
            for (var type : EnumSet.allOf(ToolDocs.nonNullClass(ProviderType.class)))
                assertTrue(providers.require(type).supports(type));
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var request =
                    new VetoRequest(
                            "test system",
                            "hello",
                            List.of(),
                            ProviderType.DEEPSEEK,
                            "test-model",
                            "test-reference",
                            LlmOptions.defaults(),
                            List.of(),
                            url,
                            true,
                            ResponseContract.ordinary());
            var response =
                    providers
                            .require(ProviderType.DEEPSEEK)
                            .execute(new ResolvedRequest(request, url, "synthetic-key"));
            assertEquals("hello", response.message());
            String sent = body.get();
            assertNotNull(sent);
            assertTrue(sent.contains("test system"));
            assertFalse(sent.contains("synthetic-key"));
            verify(audit).logLLMExchange(anyString(), eq("test-model"), anyString(), anyString());
            manager.close();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            providers
                                    .require(ProviderType.DEEPSEEK)
                                    .execute(new ResolvedRequest(request, url, "synthetic-key")));
        } finally {
            manager.close();
            server.stop(0);
        }
    }
}
