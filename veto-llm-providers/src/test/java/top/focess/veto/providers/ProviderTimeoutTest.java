package top.focess.veto.providers;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.LlmClient;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.VetoRequest;

class ProviderTimeoutTest {
    private static final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void deepSeekAcceptsAndEnforcesFractionalSecondTimeouts() throws IOException {
        try (var endpoint = new StalledEndpoint()) {
            var client =
                    new DeepSeekLlmClient(
                            endpoint.url(),
                            "synthetic-key",
                            "DeepSeek",
                            mapper,
                            (source, data) -> "");
            verifyTimeout(client, ProviderType.DEEPSEEK, endpoint);
        }
    }

    @Test
    void openAiEnforcesTheRequestTimeoutOnItsSharedSdkClient() throws IOException {
        try (var endpoint = new StalledEndpoint()) {
            var sdk =
                    OpenAIOkHttpClient.builder()
                            .apiKey("synthetic-key")
                            .baseUrl(endpoint.url())
                            .maxRetries(0)
                            .build();
            try {
                verifyTimeout(
                        new OpenAiLlmClient(sdk, true, "OpenAI", mapper, (source, data) -> ""),
                        ProviderType.OPENAI,
                        endpoint);
            } finally {
                sdk.close();
            }
        }
    }

    @Test
    void anthropicEnforcesTheRequestTimeoutOnItsSharedSdkClient() throws IOException {
        try (var endpoint = new StalledEndpoint()) {
            var sdk =
                    AnthropicOkHttpClient.builder()
                            .apiKey("synthetic-key")
                            .baseUrl(endpoint.url())
                            .maxRetries(0)
                            .build();
            try {
                verifyTimeout(
                        new AnthropicLlmClient(sdk, mapper, (source, data) -> ""),
                        ProviderType.ANTHROPIC,
                        endpoint);
            } finally {
                sdk.close();
            }
        }
    }

    @Test
    void geminiEnforcesTheRequestTimeoutOnItsSharedSdkClient() throws IOException {
        try (var endpoint = new StalledEndpoint();
                var sdk =
                        Client.builder()
                                .apiKey("synthetic-key")
                                .httpOptions(
                                        HttpOptions.builder()
                                                .baseUrl(endpoint.url())
                                                .retryOptions(
                                                        HttpRetryOptions.builder()
                                                                .attempts(1)
                                                                .build())
                                                .build())
                                .build()) {
            verifyTimeout(
                    new GeminiLlmClient(sdk, mapper, (source, data) -> ""),
                    ProviderType.GEMINI,
                    endpoint);
        }
    }

    private static void verifyTimeout(
            @NonNull LlmClient client,
            @NonNull ProviderType provider,
            @NonNull StalledEndpoint endpoint) {
        var request =
                new VetoRequest(
                        "",
                        "hello",
                        List.of(),
                        provider,
                        "synthetic-model",
                        "synthetic-reference",
                        new LlmOptions(null, null, 16, Duration.ofMillis(500), null),
                        List.of(),
                        endpoint.url(),
                        false,
                        ResponseContract.ordinary());
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () ->
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        client.complete(
                                                new ResolvedRequest(
                                                        request,
                                                        endpoint.url(),
                                                        "synthetic-key"))));
        assertTrue(endpoint.requests.get() > 0, "The request must reach the stalled endpoint");
    }

    private static final class StalledEndpoint implements AutoCloseable {
        private final @NonNull HttpServer server;
        private final @NonNull ExecutorService workers =
                Executors.newVirtualThreadPerTaskExecutor();
        private final @NonNull CountDownLatch release = new CountDownLatch(1);
        private final @NonNull AtomicInteger requests = new AtomicInteger();

        private StalledEndpoint() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext(
                    "/",
                    exchange -> {
                        requests.incrementAndGet();
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
        }

        private @NonNull String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            workers.close();
        }
    }
}
