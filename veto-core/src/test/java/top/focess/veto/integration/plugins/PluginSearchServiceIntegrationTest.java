package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.builtin.search.SearchHub;
import top.focess.veto.builtin.search.SearchOptions;
import top.focess.veto.builtin.search.SearchProtocol;
import top.focess.veto.builtin.search.SearchProvider;
import top.focess.veto.builtin.search.SearchResult;
import top.focess.veto.builtin.search.SearchServiceClient;
import top.focess.veto.builtin.web.WebSearchTool;
import top.focess.veto.plugin.runtime.*;

class PluginSearchServiceIntegrationTest {
    @Test
    void realToolCallsSelectedPluginUnderExistingPermit() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = fixture(provider(calls, false))) {
            var registry = client(fixture, fixture.sessions, "fixture");
            assertThrows(
                    SecurityException.class, () -> registry.search("query", SearchOptions.of(3)));
            assertEquals(0, calls.get());
            String result = invoke(registry);
            assertTrue(result.contains("https://example.com/java"));
            assertTrue(result.contains("Java docs"));
            assertEquals(1, calls.get());
            fixture.runtime.close();
            assertThrows(ToolExecutionException.class, () -> invoke(registry));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void unselectedAndMissingProvidersDoNotExecute() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = fixture(provider(calls, false))) {
            var unselected = mock(SessionPlugins.class);
            assertThrows(
                    ToolExecutionException.class,
                    () -> invoke(client(fixture, unselected, "fixture")));
            assertThrows(
                    ToolExecutionException.class,
                    () -> invoke(client(fixture, fixture.sessions, "missing")));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void timeoutKeepsCanonicalToolFailureWithoutPluginDiagnostic() throws Exception {
        try (var fixture = fixture(provider(new AtomicInteger(), true))) {
            var failure =
                    assertThrows(
                            ToolExecutionException.class,
                            () -> invoke(client(fixture, fixture.sessions, "fixture")));
            String message = failure.getMessage();
            if (message == null) throw new AssertionError("Missing failure message");
            assertTrue(message.contains("timed out"));
            assertFalse(message.contains("synthetic-private-diagnostic"));
        }
    }

    @Test
    void duplicateProviderNamesFailBeforePublishingRegistry() throws Exception {
        var provider = provider(new AtomicInteger(), false);
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new SearchHub(mock(PluginServices.class), List.of(provider, provider));
                });
    }

    @Test
    void builtinSearchProvidersAreDiscoveredAndConfigurationIsScoped() throws Exception {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "veto.plugins.configuration[top.focess.builtin].brave-api-key",
                                        "synthetic-key",
                                "veto.plugins.configuration[another.plugin].other", "isolated"));
        var config =
                new Binder(source)
                        .bind("veto.plugins", Bindable.of(PluginConfigurations.class))
                        .get();
        assertEquals(
                Map.of("brave-api-key", new JsonValue.StringValue("synthetic-key")),
                config.forPlugin("top.focess.builtin").values());
        assertTrue(config.forPlugin("absent").values().isEmpty());
        try (var manager =
                PluginTestSupport.manager(
                        PluginTestSupport.pluginPackages(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        config)) {
            assertEquals(
                    PluginState.ACTIVE, manager.registry().plugin("top.focess.builtin").state());
            assertEquals(
                    List.of(SearchProtocol.NAME),
                    manager.registry().entries(StandardContributionPoints.SERVICES).stream()
                            .map(e -> e.implementation().name())
                            .sorted()
                            .toList());
        }
    }

    private static @NonNull WorkflowPluginFixture fixture(@NonNull SearchProvider provider)
            throws Exception {
        return new WorkflowPluginFixture(
                List.of(
                        Contribution.of(
                                StandardContributionPoints.SERVICES,
                                "search",
                                new SearchHub(mock(PluginServices.class), List.of(provider)))));
    }

    @SuppressWarnings("override.receiver") // Anonymous test provider has javac's non-null receiver.
    private static @NonNull SearchProvider provider(@NonNull AtomicInteger calls, boolean timeout) {
        return new SearchProvider() {
            @Override
            public @NonNull String name() {
                return "fixture";
            }

            @Override
            public @NonNull List<SearchResult> search(
                    @NonNull String query, @NonNull SearchOptions options) throws Exception {
                calls.incrementAndGet();
                if (timeout) throw new HttpTimeoutException("synthetic-private-diagnostic");
                return List.of(
                        new SearchResult("Java docs", "https://example.com/java", "Reference"));
            }
        };
    }

    private static @NonNull String invoke(@NonNull SearchProvider provider) throws Exception {
        var tool = new WebSearchTool(provider);
        return CapabilityTestCalls.execute(tool, new WebSearchTool.Args("java docs", null, null));
    }

    private static @NonNull SearchServiceClient client(
            @NonNull WorkflowPluginFixture fixture,
            @NonNull SessionPlugins selected,
            @NonNull String name) {
        var registry =
                new PluginServiceRegistry(
                        (caller, provider) -> {
                            var call = ToolCallContextHolder.get();
                            if (call == null) return true;
                            var session = call.sessionId();
                            return session != null
                                    && selected.includes(session.toString(), provider)
                                    && selected.includes(session.toString(), caller);
                        });
        registry.bind(fixture.manager.registry().catalog(), List.of(fixture.runtime));
        var host = mock(PluginHost.class);
        when(host.invocation(anyString()))
                .thenAnswer(
                        call -> {
                            String tool = call.getArgument(0);
                            if (tool == null) throw new AssertionError("Missing tool name");
                            var scope =
                                    CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, tool);
                            var session = scope.sessionId();
                            if (session == null) throw new SecurityException("No session scope");
                            return new PluginHost.Invocation(
                                    "owner",
                                    session.toString(),
                                    scope.agentId(),
                                    scope.requestId(),
                                    "test-call");
                        });
        var context =
                new PluginContext(
                        fixture.runtime.identity(),
                        () -> {},
                        () -> {
                            throw new IllegalStateException(
                                    "Plugin context is not bound to a lifecycle owner");
                        },
                        Map.of(
                                PluginHost.class,
                                host,
                                PluginServices.class,
                                registry.forPlugin(fixture.runtime)),
                        Map.of());
        return new SearchServiceClient(
                context,
                new JsonValue.ObjectValue(
                        Map.of("search-provider", new JsonValue.StringValue(name))));
    }
}
