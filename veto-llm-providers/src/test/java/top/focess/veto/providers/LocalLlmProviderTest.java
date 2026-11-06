package top.focess.veto.providers;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.LocalModelRuntime;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.VetoRequest;

class LocalLlmProviderTest {
    @Test
    void fileEndpointLoadsModelAndUsesOrdinaryChatTransportWithoutNetworkCredentials()
            throws Exception {
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            ("{\"id\":\"local-1\",\"object\":\"chat.completion\",\"created\":1,"
                                            + "\"model\":\"example\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                                            + "\"message\":{\"role\":\"assistant\",\"content\":\"DONE\"}}]}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        var opens = new AtomicInteger();
        LocalModelRuntime runtime =
                (path, context) -> {
                    assertEquals("./models/example.gguf", path);
                    assertEquals(16384, context);
                    opens.incrementAndGet();
                    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
                };
        try (var clients = new LlmClientFactory(new ObjectMapper(), (source, data) -> "")) {
            new LlmClientRegistration(clients).registerBuilders();
            var provider = new LocalLlmProvider(clients, () -> runtime);
            var resolved =
                    provider.resolve(
                            request("./models/example.gguf", ""),
                            (request, endpoint) -> {
                                throw new AssertionError(
                                        "Local models must not resolve network credentials");
                            });
            assertEquals("", resolved.apiKey());
            assertEquals("DONE", provider.complete(resolved).rawResponse());
            assertEquals(1, opens.get());
            var sent = new ObjectMapper().readTree(body.get());
            assertEquals("example", sent.path("model").asText());
            assertTrue(sent.path("messages").isArray());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> provider.resolve(request(null, ""), (request, endpoint) -> resolved));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            provider.resolve(
                                    request("./models/example.gguf", "vault-key"),
                                    (request, endpoint) -> resolved));
        } finally {
            server.stop(0);
        }
    }

    private static @NonNull VetoRequest request(String endpoint, @NonNull String credential) {
        return new VetoRequest(
                "system",
                "hello",
                List.of(),
                ProviderType.LOCAL,
                "example",
                credential,
                new LlmOptions(0.0, null, 512, null, 16384),
                List.of(),
                endpoint,
                true,
                ResponseContract.ordinary());
    }
}
