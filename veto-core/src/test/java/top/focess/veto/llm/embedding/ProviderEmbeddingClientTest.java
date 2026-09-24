package top.focess.veto.llm.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.llm.credential.CredentialResolver;

class ProviderEmbeddingClientTest {
    @Test
    void configuredGeminiKeyIsHeaderOnlyAndVectorIsReturned() throws Exception {
        var header = new AtomicReference<String>();
        var uri = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    header.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
                    uri.set(exchange.getRequestURI().toString());
                    byte[] body =
                            "{\"embedding\":{\"values\":[1,0]}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var credentials = mock(ToolDocs.nonNullClass(CredentialResolver.class));
            when(credentials.resolve(ProviderType.GEMINI, "configured"))
                    .thenReturn("synthetic-private-key");
            var profile = profile(server);
            profile.setProvider("gemini");
            assertArrayEquals(
                    new float[] {1, 0},
                    new ProviderEmbeddingClient(profile, credentials, new ObjectMapper())
                            .embed("text"));
            assertEquals("synthetic-private-key", header.get());
            assertEquals("/v1beta/models/model:embedContent", uri.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseBodyIsBoundedAndProviderFailureTextIsNotExposed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body =
                            "synthetic-private-key".repeat(60_000).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try {
                        exchange.getResponseBody().write(body);
                    } catch (IOException ignored) {
                    }
                    exchange.close();
                });
        server.start();
        try {
            var credentials = mock(ToolDocs.nonNullClass(CredentialResolver.class));
            when(credentials.resolve(ProviderType.OPENAI, "configured"))
                    .thenReturn("synthetic-private-key");
            var failure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    new ProviderEmbeddingClient(
                                                    profile(server),
                                                    credentials,
                                                    new ObjectMapper())
                                            .embed("text"));
            assertFalse(failure.toString().contains("synthetic-private-key"));
            assertNull(failure.getCause());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void wholeResponseDeadlineCancelsStalledBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(200, 10_000);
                    try {
                        Thread.sleep(350);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        try {
            var credentials = mock(ToolDocs.nonNullClass(CredentialResolver.class));
            when(credentials.resolve(ProviderType.OPENAI, "configured"))
                    .thenReturn("synthetic-private-key");
            var client =
                    new ProviderEmbeddingClient(
                            profile(server),
                            credentials,
                            new ObjectMapper(),
                            Duration.ofMillis(100));
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> assertThrows(IllegalStateException.class, () -> client.embed("text")));
        } finally {
            server.stop(0);
        }
    }

    private static @NonNull EmbeddingProfile profile(@NonNull HttpServer server) {
        var profile = new EmbeddingProfile();
        profile.setProvider("openai");
        profile.setModel("model");
        profile.setDimension(2);
        profile.setCredentialKey("configured");
        profile.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return profile;
    }
}
