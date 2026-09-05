package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.capability.NetworkEgressCapabilityImpl;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;

class WebFetchToolTest {

    @Test
    void timesOutWhenServerSendsHeadersButStallsTheBody() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/slow",
                exchange -> {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    try {
                        releaseBody.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            WebFetchTool tool =
                    new WebFetchTool(
                            new NetworkEgressCapabilityImpl(
                                    mock(ToolDocs.nonNullClass(SearchProvider.class)),
                                    1,
                                    1000,
                                    true));
            long started = System.nanoTime();
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () ->
                                    CapabilityTestCalls.execute(
                                            tool,
                                            new WebFetchTool.Args(
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort()
                                                            + "/slow")));
            assertTrue(ToolErrors.normalize(error.getMessage()).contains("timed out"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @Test
    void productionPolicyRejectsPrivateDestinations() {
        WebFetchTool tool =
                new WebFetchTool(
                        new NetworkEgressCapabilityImpl(
                                mock(ToolDocs.nonNullClass(SearchProvider.class)), 5, 1000, false));

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                CapabilityTestCalls.execute(
                                        tool, new WebFetchTool.Args("http://127.0.0.1/admin")));

        assertTrue(
                ToolErrors.normalize(error.getMessage()).contains("private, loopback, link-local"));
    }

    @Test
    void followsSameOriginRedirectAndBoundsTheResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/start",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/final");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/final",
                exchange -> {
                    byte[] body = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            WebFetchTool tool =
                    new WebFetchTool(
                            new NetworkEgressCapabilityImpl(
                                    mock(ToolDocs.nonNullClass(SearchProvider.class)),
                                    5,
                                    10,
                                    true));
            String result =
                    CapabilityTestCalls.execute(
                            tool,
                            new WebFetchTool.Args(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/start"));
            assertTrue(result.contains("/final"), result);
            assertTrue(result.contains("[truncated at 10 chars]"), result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotFollowCrossOriginRedirectWithoutFreshApproval() throws Exception {
        AtomicInteger destinationHits = new AtomicInteger();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext(
                "/private",
                exchange -> {
                    destinationHits.incrementAndGet();
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                });
        destination.start();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext(
                "/start",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "http://127.0.0.1:"
                                            + destination.getAddress().getPort()
                                            + "/private");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        origin.start();
        try {
            WebFetchTool tool =
                    new WebFetchTool(
                            new NetworkEgressCapabilityImpl(
                                    mock(ToolDocs.nonNullClass(SearchProvider.class)),
                                    5,
                                    1000,
                                    true));
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () ->
                                    CapabilityTestCalls.execute(
                                            tool,
                                            new WebFetchTool.Args(
                                                    "http://127.0.0.1:"
                                                            + origin.getAddress().getPort()
                                                            + "/start")));
            String message = ToolErrors.normalize(error.getMessage());
            assertTrue(message.contains("redirect"), message);
            assertEquals(0, destinationHits.get());
        } finally {
            origin.stop(0);
            destination.stop(0);
        }
    }
}
