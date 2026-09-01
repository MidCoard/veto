package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolExecutionException;

class WebFetchToolTest {

    @Test
    void productionPolicyRejectsPrivateDestinations() {
        WebFetchTool tool = new WebFetchTool(5, 1000, false);

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> tool.execute(new WebFetchTool.Args("http://127.0.0.1/admin")));

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
            WebFetchTool tool = new WebFetchTool(5, 10);
            String result =
                    tool.execute(
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
            WebFetchTool tool = new WebFetchTool(5, 1000);
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () ->
                                    tool.execute(
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
