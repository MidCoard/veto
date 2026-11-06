package top.focess.veto.veto;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;

class LocalModelTransportTest {
    @Test
    void providerReusesCoreModelProcessForSameFileAndContext(@TempDir @NonNull Path root)
            throws Exception {
        var model = Files.writeString(root.resolve("core.gguf"), "fixture");
        var config = new SlmConfiguration();
        config.setModelPath(model.toString());
        var bridge = new LlamaCppBridge(config, new GBNFGrammarEngine(config));
        var process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        ReflectionTestUtils.setField(bridge, "llamaProcess", process);
        ReflectionTestUtils.setField(bridge, "serverPort", 18081);
        try {
            assertEquals("http://127.0.0.1:18081/v1", bridge.endpoint(model.toString(), 2048));
            assertEquals(
                    "http://127.0.0.1:18081/v1",
                    bridge.endpoint(root.resolve(".").resolve("core.gguf").toString(), 2048));
            var instances = (Map<?, ?>) ReflectionTestUtils.getField(bridge, "models");
            if (instances == null) throw new AssertionError("Missing runtime instance map");
            assertTrue(instances.isEmpty());
        } finally {
            when(process.isAlive()).thenReturn(false);
            bridge.stop();
        }
    }

    @Test
    void modelEndpointRejectsNetworkMissingAndInvalidArtifacts(@TempDir @NonNull Path root)
            throws Exception {
        var config = new SlmConfiguration();
        config.setEnabled(false);
        var shared = new LlamaCppBridge(config, new GBNFGrammarEngine(config));
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> shared.endpoint("https://example.com/model.gguf", 2048));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> shared.endpoint(root.resolve("missing.gguf").toString(), 2048));
            var model = Files.writeString(root.resolve("model.gguf"), "fixture");
            assertThrows(
                    IllegalArgumentException.class, () -> shared.endpoint(model.toString(), 0));
            assertThrows(
                    IllegalStateException.class, () -> shared.endpoint(model.toString(), 16384));
            assertEquals(2048, config.getNCtx());
            assertEquals(16384, config.forModel(model.toString(), 16384).getNCtx());
        } finally {
            shared.stop();
        }
    }

    @Test
    void interruptedReadinessClosesTheStartedProcessAndRetainsTheSignal(@TempDir @NonNull Path root)
            throws Exception {
        var config = new SlmConfiguration();
        config.setModelPath(Files.writeString(root.resolve("model.gguf"), "fixture").toString());
        var grammars = mock(GBNFGrammarEngine.class);
        when(grammars.loadVetoOutputGrammar()).thenReturn("root ::= \"x\"");
        var process = mock(Process.class);
        var alive = new AtomicBoolean(true);
        when(process.isAlive()).thenAnswer(invocation -> alive.get());
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        doAnswer(
                        invocation -> {
                            alive.set(false);
                            return null;
                        })
                .when(process)
                .destroy();
        var http = mock(HttpClient.class);
        when(http.send(
                        any(HttpRequest.class),
                        ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenThrow(new InterruptedException("readiness probe stopped"));
        var bridge = new LlamaCppBridge(config, grammars);
        ReflectionTestUtils.setField(bridge, "httpClient", http);
        try (MockedConstruction<ProcessBuilder> builders =
                mockConstruction(
                        ProcessBuilder.class,
                        (builder, context) -> when(builder.start()).thenReturn(process))) {
            assertFalse(bridge.start());
            assertEquals(1, builders.constructed().size());
            verify(process).destroy();
            assertFalse(bridge.isAvailable());
            assertNull(ReflectionTestUtils.getField(bridge, "llamaProcess"));
            assertTrue(Thread.currentThread().isInterrupted());
            var request = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http)
                    .send(
                            request.capture(),
                            ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
            assertEquals(Duration.ofMillis(500), request.getValue().timeout().orElseThrow());
        } finally {
            bridge.stop();
            assertTrue(Thread.interrupted(), "test must clear its retained cancellation signal");
        }
    }

    @Test
    void processExitImmediatelyMakesTheBridgeUnavailable() {
        var bridge =
                new LlamaCppBridge(mock(SlmConfiguration.class), mock(GBNFGrammarEngine.class));
        var process = mock(Process.class);
        var alive = new AtomicBoolean(true);
        when(process.isAlive()).thenAnswer(invocation -> alive.get());
        ReflectionTestUtils.setField(bridge, "llamaProcess", process);
        ReflectionTestUtils.setField(bridge, "serverPort", 8080);
        assertTrue(bridge.isAvailable());
        alive.set(false);
        assertFalse(bridge.isAvailable());
        bridge.stop();
        assertNull(ReflectionTestUtils.getField(bridge, "llamaProcess"));
    }

    @Test
    void cancellingLocalCompletionCancelsThePendingHttpRequest() {
        SlmConfiguration config = mock(SlmConfiguration.class);
        GBNFGrammarEngine grammars = mock(GBNFGrammarEngine.class);
        HttpClient http = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<String>>();
        when(http.sendAsync(
                        any(HttpRequest.class),
                        ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(pending);
        var bridge = new LlamaCppBridge(config, grammars);
        ReflectionTestUtils.setField(bridge, "httpClient", http);
        var process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        ReflectionTestUtils.setField(bridge, "llamaProcess", process);
        ReflectionTestUtils.setField(bridge, "serverPort", 8080);
        when(grammars.resolveGrammar("veto-output")).thenReturn("root ::= \"x\"");
        var result = bridge.infer("compiled content", "veto-output");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http)
                .sendAsync(
                        request.capture(),
                        ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals(Duration.ofSeconds(30), request.getValue().timeout().orElseThrow());
        assertTrue(result.cancel(true));
        assertTrue(pending.isCancelled());
    }

    @Test
    void namedInferencePreservesFailureFallback() throws Exception {
        var http = mock(HttpClient.class);
        var grammars = mock(GBNFGrammarEngine.class);
        when(grammars.resolveGrammar("veto-output")).thenReturn("root ::= \"x\"");
        when(http.sendAsync(
                        any(HttpRequest.class),
                        ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("unavailable")));
        var bridge = new LlamaCppBridge(mock(SlmConfiguration.class), grammars);
        ReflectionTestUtils.setField(bridge, "httpClient", http);
        var process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        ReflectionTestUtils.setField(bridge, "llamaProcess", process);
        ReflectionTestUtils.setField(bridge, "serverPort", 8080);

        assertEquals(
                "{\"veto_decision\":\"pass\",\"data\":{\"error\":\"unavailable\"}}",
                bridge.infer("prompt", "veto-output").get());

        var response = mock(HttpResponse.class);
        var callbackFailure = new AssertionError("Response mapping failed");
        when(response.statusCode()).thenThrow(callbackFailure);
        doReturn(CompletableFuture.completedFuture(response))
                .when(http)
                .sendAsync(
                        any(HttpRequest.class),
                        ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        var result = bridge.infer("prompt", "veto-output");
        var failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() == callbackFailure);
    }
}
