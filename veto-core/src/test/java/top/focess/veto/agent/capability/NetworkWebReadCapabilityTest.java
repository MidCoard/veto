package top.focess.veto.agent.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolContractValidator;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.agent.web.SearchProvider;
import top.focess.veto.agent.web.WebReadTool;
import top.focess.veto.agent.web.WebReader;

class NetworkWebReadCapabilityTest {
    private final @NonNull WebReader reader = mock(ToolDocs.nonNullClass(WebReader.class));

    @Test
    void publicToolPassesRestrictedDependencyRegistrationContract() {
        WebReadTool tool = new WebReadTool(network(false));
        assertDoesNotThrow(
                () ->
                        ToolContractValidator.validateHandler(
                                tool, ToolSchemaCompiler.compileNative(tool)));
    }

    @Test
    void cannotOpenOutsideTheApprovedCallOrChangeItsDestination() throws Exception {
        NetworkEgressCapabilityImpl network = network(true);
        URI approved = URI.create("http://127.0.0.1:1/approved");
        assertThrows(SecurityException.class, () -> network.openReader(approved));
        WebReadTool tool =
                tool(
                        network,
                        invocation -> {
                            assertThrows(
                                    SecurityException.class,
                                    () ->
                                            network.openReader(
                                                    URI.create("http://127.0.0.1:1/other")));
                            return "destination stayed bound";
                        });
        assertEquals("destination stayed bound", execute(tool, approved));
    }

    @Test
    void followsSameOriginRedirectAndCachesOnlyInsideTheLiveInvocation() throws Exception {
        AtomicInteger destinationRequests = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/start", exchange -> redirect(exchange, "/document"));
        server.createContext(
                "/document",
                exchange -> {
                    destinationRequests.incrementAndGet();
                    respond(exchange, "The timeout is 30 seconds.");
                });
        server.start();
        AtomicReference<WebReadCapability> captured = new AtomicReference<>();
        try {
            WebReadTool tool =
                    tool(
                            network(true),
                            invocation -> {
                                WebReadCapability access = invocation.getArgument(1);
                                if (access == null)
                                    throw new AssertionError("Missing reader capability");
                                captured.set(access);
                                var first = access.fetch(deadline());
                                assertEquals(url(server, "/document"), first.uri());
                                assertSame(first, access.fetch(deadline()));
                                return first.content();
                            });
            assertEquals("The timeout is 30 seconds.", execute(tool, url(server, "/start")));
            assertEquals(1, destinationRequests.get());
            WebReadCapability access = captured.get();
            assertNotNull(access);
            assertThrows(SecurityException.class, () -> access.fetch(deadline()));
            assertEquals(1, destinationRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesCrossOriginRedirectBeforeSendingAnyRequestToItsTarget() throws Exception {
        AtomicInteger targetRequests = new AtomicInteger();
        HttpServer origin = server();
        HttpServer target = server();
        target.createContext(
                "/target",
                exchange -> {
                    targetRequests.incrementAndGet();
                    respond(exchange, "must never be read");
                });
        origin.createContext(
                "/start", exchange -> redirect(exchange, url(target, "/target").toString()));
        target.start();
        origin.start();
        try {
            WebReadTool tool = fetchingTool(network(true));
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () -> execute(tool, url(origin, "/start")));
            String message = error.content();
            assertTrue(message.contains("cross-origin redirect"));
            assertEquals(0, targetRequests.get());
        } finally {
            origin.stop(0);
            target.stop(0);
        }
    }

    @Test
    void productionPrivateAddressPolicyRefusesBeforeConnecting() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server();
        server.createContext(
                "/private",
                exchange -> {
                    requests.incrementAndGet();
                    respond(exchange, "private");
                });
        server.start();
        try {
            WebReadTool tool = fetchingTool(network(false));
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () -> execute(tool, url(server, "/private")));
            String message = error.content();
            assertTrue(message.contains("private, loopback"));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachedDocumentCannotBeReadInALaterCallEvenBeforeExplicitClose() throws Exception {
        HttpServer server = server();
        AtomicInteger requests = new AtomicInteger();
        server.createContext(
                "/document",
                exchange -> {
                    requests.incrementAndGet();
                    respond(exchange, "source");
                });
        server.start();
        AtomicReference<WebReadCapability> captured = new AtomicReference<>();
        NetworkEgressCapabilityImpl network = network(true);
        try {
            URI uri = url(server, "/document");
            execute(
                    tool(
                            network,
                            invocation -> {
                                WebReadCapability extra = network.openReader(uri);
                                captured.set(extra);
                                return extra.fetch(deadline()).content();
                            }),
                    uri);
            WebReadCapability extra = captured.get();
            assertNotNull(extra);
            assertThrows(SecurityException.class, () -> extra.fetch(deadline()));
            execute(
                    tool(
                            network,
                            invocation -> {
                                assertThrows(
                                        SecurityException.class, () -> extra.fetch(deadline()));
                                return "new invocation cannot reuse cached authority";
                            }),
                    uri);
            assertEquals(1, requests.get());
            extra.close();
            assertThrows(SecurityException.class, () -> extra.fetch(deadline()));
        } finally {
            WebReadCapability extra = captured.get();
            if (extra != null) extra.close();
            server.stop(0);
        }
    }

    private @NonNull NetworkEgressCapabilityImpl network(boolean allowPrivate) {
        return new NetworkEgressCapabilityImpl(
                mock(ToolDocs.nonNullClass(SearchProvider.class)), reader, 5, 10000, allowPrivate);
    }

    private @NonNull WebReadTool fetchingTool(@NonNull NetworkEgressCapabilityImpl network) {
        return tool(
                network,
                invocation -> {
                    WebReadCapability access = invocation.getArgument(1);
                    if (access == null) throw new AssertionError("Missing reader capability");
                    return access.fetch(deadline()).content();
                });
    }

    private @NonNull WebReadTool tool(
            @NonNull NetworkEgressCapabilityImpl network, @NonNull Answer<String> action) {
        when(reader.read(anyString(), any(ToolDocs.nonNullClass(WebReadCapability.class))))
                .thenAnswer(action);
        return new WebReadTool(network);
    }

    private static @NonNull String execute(@NonNull WebReadTool tool, @NonNull URI uri)
            throws Exception {
        return CapabilityTestCalls.execute(
                tool, new WebReadTool.Args(uri.toString(), "Read timeout units."));
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    private static @NonNull HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static @NonNull URI url(@NonNull HttpServer server, @NonNull String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void redirect(@NonNull HttpExchange exchange, @NonNull String location)
            throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(@NonNull HttpExchange exchange, @NonNull String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
