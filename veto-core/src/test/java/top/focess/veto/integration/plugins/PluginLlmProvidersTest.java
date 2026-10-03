package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.*;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.observability.AuditLogger;
import top.focess.veto.plugin.runtime.*;

class PluginLlmProvidersTest {
    @Test
    void providerMetadataIsCapturedAtPreparationAndNeverCallsTheRetiredPlugin() throws Exception {
        var implementation = mock(LlmProvider.class);
        when(implementation.type()).thenReturn(ProviderType.OPENAI);
        when(implementation.defaultBaseUrl()).thenReturn("https://prepared.example");
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.LLM_PROVIDERS,
                                        "provider",
                                        implementation)))) {
            var providers =
                    new PluginLlmProviders(
                            fixture.manager,
                            new ObjectMapper(),
                            mock(AuditLogger.class),
                            fixture.sessions);
            var strategy = providers.require(ProviderType.OPENAI);
            assertEquals("https://prepared.example", strategy.defaultBaseUrl());
            when(implementation.defaultBaseUrl())
                    .thenThrow(new AssertionError("Runtime metadata callback"));
            fixture.runtime.close();
            assertEquals("https://prepared.example", strategy.defaultBaseUrl());
            verify(implementation, times(1)).defaultBaseUrl();
        }
    }

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
            var audit = Objects.requireNonNull(mock(AuditLogger.class), "Mockito returned null");
            var selections =
                    Objects.requireNonNull(mock(SessionPlugins.class), "Mockito returned null");
            var providers = new PluginLlmProviders(manager, new ObjectMapper(), audit, selections);
            for (var type : EnumSet.allOf(ProviderType.class))
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

    @Test
    void sessionProviderRequiresExactPinnedPluginRevision() throws Exception {
        var manager = PluginTestSupport.manager();
        try {
            var selections =
                    Objects.requireNonNull(mock(SessionPlugins.class), "Mockito returned null");
            var source =
                    manager.registry()
                            .entries(StandardContributionPoints.LLM_PROVIDERS)
                            .get(0)
                            .source();
            var runtime = manager.registry().plugin(source.namespace());
            var exact =
                    new PluginBinding(
                            runtime.identity().id(),
                            runtime.identity().version(),
                            runtime.identity().version());
            var providers =
                    new PluginLlmProviders(
                            manager, new ObjectMapper(), mock(AuditLogger.class), selections);

            when(selections.bindings("session-1")).thenReturn(List.of(exact));
            assertNotNull(providers.require(ProviderType.OPENAI, "session-1"));

            when(selections.bindings("session-1"))
                    .thenReturn(
                            List.of(
                                    new PluginBinding(
                                            exact.id(), exact.version(), "different-revision")));
            var mismatch =
                    assertThrows(
                            ModelCapabilityException.class,
                            () -> providers.require(ProviderType.OPENAI, "session-1"));
            var mismatchMessage = mismatch.getMessage();
            if (mismatchMessage == null) throw new AssertionError("Missing mismatch diagnostic");
            assertTrue(mismatchMessage.contains("different-revision"));
            assertTrue(mismatchMessage.contains("Restore the pinned plugin revision"));

            when(selections.bindings("session-1")).thenReturn(List.of());
            var absent =
                    assertThrows(
                            ModelCapabilityException.class,
                            () -> providers.require(ProviderType.OPENAI, "session-1"));
            var absentMessage = absent.getMessage();
            if (absentMessage == null) throw new AssertionError("Missing selection diagnostic");
            assertTrue(absentMessage.contains("not selected for this session"));
        } finally {
            manager.close();
        }
    }
}
