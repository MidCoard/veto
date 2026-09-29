package top.focess.veto.builtin.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.builtin.BuiltinPlugin;

class BuiltinSearchProviderTest {
    @Test
    void pluginLoadsAndContributesWithOnlyApiAndLibraryDependencies() throws Exception {
        var configuration =
                new JsonValue.ObjectValue(
                        Map.of("brave-api-key", new JsonValue.StringValue("synthetic")));
        var context =
                new PluginContext(
                        new PluginIdentity("top.focess.builtin", "1.0.100"),
                        () -> {},
                        () -> {
                            throw new IllegalStateException("Plugin context is not bound");
                        },
                        Map.of(PluginHost.class, mock(PluginHost.class)));
        var plugin = new BuiltinPlugin(context, configuration);
        try {
            var contributions = context.sealRegistrations();
            assertEquals("top.focess.builtin", plugin.identity().id());
            var providers =
                    contributions.entries().stream()
                            .filter(e -> e.point().equals(StandardContributionPoints.SERVICES))
                            .toList();
            assertEquals(
                    List.of("search"), providers.stream().map(e -> e.localId()).sorted().toList());
            assertTrue(
                    providers.stream()
                            .allMatch(e -> e.point().equals(StandardContributionPoints.SERVICES)));
            plugin.start();
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("top.focess.veto.agent.AgentRunner"));
        } finally {
            plugin.close();
        }
    }

    @Test
    void duckDuckGoParsesRedirectsAndAppliesDomainFiltersAndCap() throws Exception {
        String body =
                """
                <div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">First</a><a class="result__snippet">One</a></div>
                <div class="result"><a class="result__a" href="https://blocked.test/b">Blocked</a></div>
                <div class="result"><a class="result__a" href="https://example.com/c">Third</a></div>
                """;
        var server = server(body, new AtomicReference<>());
        try (var provider =
                new DuckDuckGoSearchProvider(HttpClient.newHttpClient(), endpoint(server))) {
            var results =
                    provider.search(
                            "java docs", new SearchOptions(List.of("example.com"), null, 1));
            assertEquals(1, results.size());
            assertEquals("https://example.com/a", results.getFirst().url());
            assertEquals("One", results.getFirst().snippet());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void braveSendsConfiguredKeyAndParsesResults() throws Exception {
        var key = new AtomicReference<String>();
        var server =
                server(
                        """
                {"web":{"results":[{"title":"Java","url":"https://example.com/java","description":"Docs"}]}}
                """,
                        key);
        try (var provider =
                new BraveSearchProvider(
                        "synthetic-key", HttpClient.newHttpClient(), endpoint(server))) {
            var results = provider.search("java docs", SearchOptions.of(3));
            assertEquals("synthetic-key", key.get());
            assertEquals("Docs", results.getFirst().snippet());
        } finally {
            server.stop(0);
        }
        try (var provider = new BraveSearchProvider("")) {
            assertThrows(
                    IllegalStateException.class,
                    () -> provider.search("java docs", SearchOptions.of(3)));
        }
    }

    private static @NonNull HttpServer server(
            @NonNull String body, @NonNull AtomicReference<String> key) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    key.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        return server;
    }

    private static @NonNull String endpoint(@NonNull HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/?q=";
    }
}
