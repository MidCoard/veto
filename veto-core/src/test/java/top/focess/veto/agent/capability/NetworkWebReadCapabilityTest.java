package top.focess.veto.agent.capability;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolContractValidator;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.*;
import top.focess.veto.api.http.ApprovedHttpDestination;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/** Exercises host URL authority without depending on a feature tool or model reader. */
class NetworkWebReadCapabilityTest {
    @ToolSecurity(capability = ToolCapability.NETWORK_EGRESS, defaultDanger = Danger.SAFE)
    @ToolDoc(
            description = "HTTP grant authorization probe",
            behavior = "Returns a probe marker after host authorization.",
            whenToUse = "Testing approved HTTP destination capture.",
            whenNotToUse = "Reading remote content.",
            resultContract = "Returns the plain text probe marker.",
            errorsAndEdgeCases = "Host authorization rejects unapproved destinations.",
            security = "Only the URL annotated argument grants destination authority.",
            resultFormats = ToolResultFormat.PLAINTEXT,
            examples = {
                "{\"url\":\"https://example.com\",\"otherUrl\":\"unused\"}",
                "{\"url\":\"https://example.org/status\",\"otherUrl\":\"unused\"}",
                "{\"url\":\"https://example.net/health\",\"otherUrl\":\"unused\"}"
            },
            returnExamples = {"probe", "probe", "probe"})
    static final class HttpProbe implements NativeTool<HttpProbe.Args> {
        record Args(
                @SecurityHint(ParamCategory.URL) @NonNull String url, @NonNull String otherUrl) {}

        public @NonNull String getName() {
            return "operator_http_probe";
        }

        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        public @NonNull String execute(@NonNull Args args) {
            return "probe";
        }
    }

    @Test
    void genericNetworkToolPassesRegistrationContract() {
        var tool = new HttpProbe();
        assertDoesNotThrow(
                () ->
                        ToolContractValidator.validateHandler(
                                tool, ToolSchemaCompiler.compileNative(tool)));
    }

    @Test
    void cannotOpenOutsideTheApprovedCallOrMintAuthorityFromUnmarkedArguments() throws Exception {
        var network = network(true);
        URI approved = URI.create("http://127.0.0.1:1/approved");
        assertThrows(SecurityException.class, () -> network.openApprovedDestination("url"));
        inCall(
                approved,
                () -> {
                    assertThrows(
                            SecurityException.class,
                            () -> network.openApprovedDestination("otherUrl"));
                    assertThrows(
                            SecurityException.class,
                            () -> network.openApprovedDestination("http://127.0.0.1:1/other"));
                    try (var grant = network.openApprovedDestination("url")) {
                        assertNotNull(grant);
                    }
                    return "bound";
                });
    }

    @Test
    void followsSameOriginRedirectAndCachesOnlyInsideTheLiveInvocation() throws Exception {
        var requests = new AtomicInteger();
        var server = server();
        server.createContext("/start", exchange -> redirect(exchange, "/document"));
        server.createContext(
                "/document",
                exchange -> {
                    requests.incrementAndGet();
                    respond(exchange, "The timeout is 30 seconds.");
                });
        server.start();
        var captured = new AtomicReference<ApprovedHttpDestination>();
        try {
            assertEquals(
                    "The timeout is 30 seconds.",
                    inCall(
                            url(server, "/start"),
                            () -> {
                                try (var grant = network(true).openApprovedDestination("url")) {
                                    captured.set(grant);
                                    var first = grant.fetch();
                                    assertEquals(url(server, "/document"), first.uri());
                                    assertSame(first, grant.fetch());
                                    return first.content();
                                }
                            }));
            assertEquals(1, requests.get());
            var grant = captured.get();
            if (grant == null) throw new AssertionError("Grant not captured");
            assertThrows(SecurityException.class, grant::fetch);
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesCrossOriginRedirectBeforeSendingAnyRequestToItsTarget() throws Exception {
        var requests = new AtomicInteger();
        var origin = server();
        var target = server();
        target.createContext(
                "/target",
                exchange -> {
                    requests.incrementAndGet();
                    respond(exchange, "never read");
                });
        origin.createContext(
                "/start", exchange -> redirect(exchange, url(target, "/target").toString()));
        target.start();
        origin.start();
        try {
            var error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () -> fetch(network(true), url(origin, "/start")));
            assertTrue(error.content().contains("cross-origin redirect"));
            assertEquals(0, requests.get());
        } finally {
            origin.stop(0);
            target.stop(0);
        }
    }

    @Test
    void productionPrivateAddressPolicyRefusesBeforeConnecting() throws Exception {
        var requests = new AtomicInteger();
        var server = server();
        server.createContext(
                "/private",
                exchange -> {
                    requests.incrementAndGet();
                    respond(exchange, "private");
                });
        server.start();
        try {
            var error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () -> fetch(network(false), url(server, "/private")));
            assertTrue(error.content().contains("private, loopback"));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachedDocumentCannotBeReadInALaterCallEvenWithIdenticalCallerScope() throws Exception {
        var server = server();
        var requests = new AtomicInteger();
        server.createContext(
                "/document",
                exchange -> {
                    requests.incrementAndGet();
                    respond(exchange, "source");
                });
        server.start();
        var captured = new AtomicReference<ApprovedHttpDestination>();
        var network = network(true);
        try {
            URI uri = url(server, "/document");
            assertEquals(
                    "source",
                    inCall(
                            uri,
                            () -> {
                                var grant = network.openApprovedDestination("url");
                                captured.set(grant);
                                return grant.fetch().content();
                            }));
            var grant = captured.get();
            if (grant == null) throw new AssertionError("Grant not captured");
            assertThrows(SecurityException.class, grant::fetch);
            inCall(
                    uri,
                    () -> {
                        assertThrows(SecurityException.class, grant::fetch);
                        return "denied";
                    });
            assertEquals(1, requests.get());
            grant.close();
            assertThrows(SecurityException.class, grant::fetch);
        } finally {
            var grant = captured.get();
            if (grant != null) grant.close();
            server.stop(0);
        }
    }

    @Test
    void parentCancellationRevokesEvenCachedDocumentAccess() throws Exception {
        var requests = new AtomicInteger();
        var server = server();
        server.createContext(
                "/document",
                exchange -> {
                    requests.incrementAndGet();
                    respond(exchange, "source");
                });
        server.start();
        try {
            inCall(
                    url(server, "/document"),
                    () -> {
                        try (var grant = network(true).openApprovedDestination("url")) {
                            assertEquals("source", grant.fetch().content());
                            Thread.currentThread().interrupt();
                            try {
                                assertThrows(SecurityException.class, grant::fetch);
                            } finally {
                                Thread.interrupted();
                            }
                            assertEquals(1, requests.get());
                            return "cancelled";
                        }
                    });
        } finally {
            Thread.interrupted();
            server.stop(0);
        }
    }

    @Test
    void fetchTimesOutWhenHeadersArriveButBodyStalls() throws Exception {
        var release = new CountDownLatch(1);
        var server = server();
        server.createContext(
                "/slow",
                exchange -> {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            long started = System.nanoTime();
            var error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () ->
                                    fetch(
                                            new NetworkEgressCapabilityImpl(1, 1000, true),
                                            url(server, "/slow")));
            assertTrue(error.content().contains("timed out"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void fetchBoundsResponseAfterSameOriginRedirect() throws Exception {
        var server = server();
        server.createContext("/start", exchange -> redirect(exchange, "/document"));
        server.createContext(
                "/document", exchange -> respond(exchange, "abcdefghijklmnopqrstuvwxyz".repeat(3)));
        server.start();
        try {
            inCall(
                    url(server, "/start"),
                    () -> {
                        try (var grant =
                                new NetworkEgressCapabilityImpl(5, 10, true)
                                        .openApprovedDestination("url")) {
                            var page = grant.fetch();
                            assertEquals(url(server, "/document"), page.uri());
                            assertEquals(10, page.maxChars());
                            assertTrue(page.truncated());
                            assertEquals(40, page.content().length());
                            return "bounded";
                        }
                    });
        } finally {
            server.stop(0);
        }
    }

    private static @NonNull NetworkEgressCapabilityImpl network(boolean allowPrivate) {
        return new NetworkEgressCapabilityImpl(5, 10000, allowPrivate);
    }

    private static @NonNull String fetch(
            @NonNull NetworkEgressCapabilityImpl network, @NonNull URI uri) throws Exception {
        return inCall(
                uri,
                () -> {
                    try (var grant = network.openApprovedDestination("url")) {
                        return grant.fetch().content();
                    }
                });
    }

    @FunctionalInterface
    private interface Operation {
        @NonNull String run() throws Exception;
    }

    private static @NonNull String inCall(@NonNull URI uri, @NonNull Operation operation)
            throws Exception {
        var definition = ToolSchemaCompiler.compileNative(new HttpProbe());
        var call =
                new ToolCall(
                        definition.name(),
                        Map.of("url", uri.toString(), "otherUrl", uri.toString()),
                        UUID.randomUUID().toString());
        var user = UUID.nameUUIDFromBytes("http-user".getBytes(StandardCharsets.UTF_8));
        var session = UUID.nameUUIDFromBytes("http-session".getBytes(StandardCharsets.UTF_8));
        var permit =
                ToolExecutionPermit.capture(
                                call, definition, Workspace.single(Path.of("."), PathMode.REAL))
                        .withCaller("http-agent", user, "owner", session);
        var previous = ToolCallContextHolder.get();
        String previousCall = ToolCallContextHolder.currentCallId();
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "http-agent",
                        user,
                        "owner",
                        session,
                        ToolResultPresentationMode.BASIC,
                        permit));
        ReflectionTestUtils.invokeMethod(
                ToolCallContextHolder.class, "setCurrentCallId", call.callId());
        try {
            return operation.run();
        } finally {
            if (previous == null) ToolCallContextHolder.clear();
            else {
                ToolCallContextHolder.set(previous);
                ReflectionTestUtils.invokeMethod(
                        ToolCallContextHolder.class,
                        "setCurrentCallId",
                        previousCall == null ? "" : previousCall);
            }
        }
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
