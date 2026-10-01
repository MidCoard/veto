package top.focess.veto.builtin.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceCallContext;

class SearchHubTest {
    @Test
    void externalProviderUsesOpaqueCallbackAndDisappearsOnRevocation() throws Exception {
        var services = mock(PluginServices.class);
        var callback = mock(PluginServices.CallbackHandle.class);
        when(callback.providerId()).thenReturn("example.provider");
        when(services.findCallback("opaque-1")).thenReturn(Optional.of(callback));
        when(callback.invoke(any()))
                .thenReturn(
                        SearchProtocol.encodeResults(
                                List.of(
                                        new SearchResult(
                                                "Example", "https://example.org", "hit"))));
        var hub = new SearchHub(services, List.of());
        var caller =
                new ServiceCallContext(
                        "example.provider", PluginScope.APPLICATION, new Scope.GlobalScope(), null);
        hub.invoke(
                caller,
                new JsonValue.ObjectValue(
                        Map.of(
                                "op", new JsonValue.StringValue("register"),
                                "name", new JsonValue.StringValue("example"),
                                "callback", new JsonValue.StringValue("opaque-1"))));
        assertEquals(
                List.of(new JsonValue.StringValue("example")),
                ((JsonValue.ArrayValue) hub.invoke(caller, operation("providers"))).values());
        assertEquals(
                "Example",
                SearchProtocol.results(
                                hub.invoke(
                                        caller,
                                        SearchProtocol.searchRequest(
                                                "example", "query", SearchOptions.of(3))))
                        .getFirst()
                        .title());
        when(callback.invoke(any()))
                .thenReturn(
                        SearchProtocol.encodeResults(
                                List.of(
                                        new SearchResult(
                                                "blocked", "https://blocked.EXAMPLE.ORG", ""),
                                        new SearchResult("kept", "https://EXAMPLE.ORG/one", ""),
                                        new SearchResult("extra", "https://example.org/two", ""))));
        assertEquals(
                List.of(new SearchResult("kept", "https://EXAMPLE.ORG/one", "")),
                SearchProtocol.results(
                        hub.invoke(
                                caller,
                                SearchProtocol.searchRequest(
                                        "example",
                                        "query",
                                        new SearchOptions(
                                                List.of("example.org"),
                                                List.of("blocked.example.org"),
                                                1)))));
        when(services.findCallback("opaque-1")).thenReturn(Optional.empty());
        assertTrue(
                ((JsonValue.ArrayValue) hub.invoke(caller, operation("providers")))
                        .values()
                        .isEmpty());
    }

    private static JsonValue.@NonNull ObjectValue operation(@NonNull String name) {
        return new JsonValue.ObjectValue(Map.of("op", new JsonValue.StringValue(name)));
    }
}
