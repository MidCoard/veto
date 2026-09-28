package top.focess.veto.builtin.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.plugin.service.ServiceScope;
import top.focess.veto.builtin.search.SearchOptions;
import top.focess.veto.builtin.search.SearchProvider;
import top.focess.veto.builtin.search.SearchResult;
import top.focess.veto.builtin.search.SearchServices;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class SearchServiceClientTest {
    @Test
    void defaultAndThirdPartySelectionUseNamedProtocolWithoutCaching() throws Exception {
        var host = mock(PluginHost.class);
        var services = mock(PluginServices.class);
        var handle = mock(PluginServices.Handle.class);
        when(services.find("veto.search:third-party", 1)).thenReturn(Optional.of(handle));
        var options = new SearchOptions(List.of("example.com"), List.of("blocked.com"), 3);
        var registration =
                SearchServices.registration(
                        new SearchProvider() {
                            public @NonNull String name() {
                                return "third-party";
                            }

                            public @NonNull List<SearchResult> search(
                                    @NonNull String query, @NonNull SearchOptions actual) {
                                assertEquals("query", query);
                                assertEquals(options, actual);
                                return List.of(
                                        new SearchResult("Title", "https://example.com", "text"));
                            }
                        });
        when(handle.invoke(any()))
                .thenAnswer(
                        call -> {
                            JsonValue request = call.getArgument(0);
                            if (request == null) throw new AssertionError("Missing request");
                            return registration
                                    .handler()
                                    .invoke(
                                            new ServiceCallContext(
                                                    "test.consumer",
                                                    ServiceScope.GLOBAL,
                                                    null,
                                                    null,
                                                    null),
                                            request);
                        });
        var context = context(host, services);
        assertEquals(
                "duckduckgo",
                new SearchServiceClient(context, new JsonValue.ObjectValue(Map.of())).name());
        var client =
                new SearchServiceClient(
                        context,
                        new JsonValue.ObjectValue(
                                Map.of(
                                        "search-provider",
                                        new JsonValue.StringValue("third-party"))));
        assertEquals("Title", client.search("query", options).getFirst().title());
        verify(host).invocation("web_search");
        when(services.find("veto.search:third-party", 1)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> client.search("query", options));
        verify(handle, times(1)).invoke(any());
    }

    @Test
    void missingPermitStopsBeforeServiceDiscovery() {
        var host = mock(PluginHost.class);
        var services = mock(PluginServices.class);
        when(host.invocation("web_search")).thenThrow(new SecurityException("No permit"));
        var client =
                new SearchServiceClient(
                        context(host, services), new JsonValue.ObjectValue(Map.of()));
        assertThrows(
                SecurityException.class,
                () -> client.search("query", new SearchOptions(null, null, 3)));
        verifyNoInteractions(services);
    }

    @Test
    void serviceErrorsRetainCanonicalTimeoutAndSafeFailure() throws Exception {
        var host = mock(PluginHost.class);
        var services = mock(PluginServices.class);
        var handle = mock(PluginServices.Handle.class);
        when(services.find("veto.search:duckduckgo", 1)).thenReturn(Optional.of(handle));
        var client =
                new SearchServiceClient(
                        context(host, services), new JsonValue.ObjectValue(Map.of()));
        when(handle.invoke(any())).thenThrow(new ServiceException(ServiceException.Code.TIMEOUT));
        assertThrows(
                HttpTimeoutException.class,
                () -> client.search("query", new SearchOptions(null, null, 3)));
        doThrow(new ServiceException(ServiceException.Code.FAILED)).when(handle).invoke(any());
        assertEquals(
                "Selected search provider failed",
                assertThrows(
                                IllegalStateException.class,
                                () -> client.search("query", new SearchOptions(null, null, 3)))
                        .getMessage());
    }

    private static @NonNull PluginContext context(
            @NonNull PluginHost host, @NonNull PluginServices services) {
        return new PluginContext(
                new PluginIdentity("top.focess.builtin", "1.0.0"),
                () -> {},
                () -> {
                    throw new IllegalStateException(
                            "Plugin context is not bound to a lifecycle owner");
                },
                Map.of(
                        PluginHost.class, host,
                        PluginServices.class, services));
    }
}
