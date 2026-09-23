package top.focess.veto.plugin.runtime;

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
import top.focess.veto.agent.capability.NetworkEgressCapabilityImpl;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.web.WebFetchExecutor;
import top.focess.veto.agent.web.WebSearchTool;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.search.SearchOptions;
import top.focess.veto.api.search.SearchProvider;
import top.focess.veto.api.search.SearchResult;

class PluginSearchProviderTest {
    @Test
    void realToolCallsSelectedPluginUnderExistingPermit() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = fixture(provider(calls, false))) {
            var registry = new PluginSearchProvider(fixture.manager, fixture.sessions, "fixture");
            assertThrows(
                    SecurityException.class, () -> registry.search("query", SearchOptions.of(3)));
            assertEquals(0, calls.get());
            String result = invoke(registry);
            assertTrue(result.contains("https://example.com/java"));
            assertTrue(result.contains("Java docs"));
            assertEquals(1, calls.get());
            fixture.runtime.close();
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class), () -> invoke(registry));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void unselectedAndMissingProvidersDoNotExecute() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = fixture(provider(calls, false))) {
            var unselected = mock(ToolDocs.nonNullClass(SessionPlugins.class));
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () -> invoke(new PluginSearchProvider(fixture.manager, unselected, "fixture")));
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            invoke(
                                    new PluginSearchProvider(
                                            fixture.manager, fixture.sessions, "missing")));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void timeoutKeepsCanonicalToolFailureWithoutPluginDiagnostic() throws Exception {
        try (var fixture = fixture(provider(new AtomicInteger(), true))) {
            var failure =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () ->
                                    invoke(
                                            new PluginSearchProvider(
                                                    fixture.manager, fixture.sessions, "fixture")));
            String message = failure.getMessage();
            if (message == null) throw new AssertionError("Missing failure message");
            assertTrue(message.contains("timed out"));
            assertFalse(message.contains("synthetic-private-diagnostic"));
        }
    }

    @Test
    void duplicateProviderNamesFailBeforePublishingRegistry() throws Exception {
        var provider = provider(new AtomicInteger(), false);
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.SEARCH_PROVIDERS,
                                        "one",
                                        provider),
                                Contribution.of(
                                        StandardContributionPoints.SEARCH_PROVIDERS,
                                        "two",
                                        provider)))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new PluginSearchProvider(fixture.manager, fixture.sessions, "fixture"));
        }
    }

    @Test
    void bundledSearchPluginIsDiscoveredAndConfigurationIsScoped() throws Exception {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "veto.plugins.configuration[top.focess.web-search].brave-api-key",
                                        "synthetic-key",
                                "veto.plugins.configuration[another.plugin].other", "isolated"));
        var config =
                new Binder(source)
                        .bind("veto.plugins", Bindable.of(PluginConfigurations.class))
                        .get();
        assertEquals(
                Map.of("brave-api-key", new JsonValue.StringValue("synthetic-key")),
                config.forPlugin("top.focess.web-search").values());
        assertTrue(config.forPlugin("absent").values().isEmpty());
        try (var manager =
                new PluginManager(
                        "", "", false, 5000, PluginTestSupport.providerOf(null), config)) {
            assertEquals(PluginState.ACTIVE, manager.plugin("top.focess.web-search").state());
            assertEquals(
                    List.of("brave", "duckduckgo"),
                    manager.catalog().entries(StandardContributionPoints.SEARCH_PROVIDERS).stream()
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
                                StandardContributionPoints.SEARCH_PROVIDERS, "search", provider)));
    }

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
        var tool =
                new WebSearchTool(
                        new NetworkEgressCapabilityImpl(
                                provider,
                                mock(ToolDocs.nonNullClass(WebFetchExecutor.class)),
                                5,
                                1000,
                                false));
        return CapabilityTestCalls.execute(tool, new WebSearchTool.Args("java docs", null, null));
    }
}
