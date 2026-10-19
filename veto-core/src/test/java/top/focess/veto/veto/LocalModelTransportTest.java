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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;

class LocalModelTransportTest {
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingLocalCompletionCancelsThePendingHttpRequest(boolean namedGrammar) {
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
        if (namedGrammar) when(grammars.resolveGrammar("veto-output")).thenReturn("root ::= \"x\"");
        var result =
                namedGrammar
                        ? bridge.infer("compiled content", "veto-output")
                        : bridge.inferWithGrammar("compiled content", "root ::= \"x\"");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http)
                .sendAsync(
                        request.capture(),
                        ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals(
                Duration.ofSeconds(namedGrammar ? 30 : 2),
                request.getValue().timeout().orElseThrow());
        assertTrue(result.cancel(true));
        assertTrue(pending.isCancelled());
        if (!namedGrammar) verifyNoInteractions(grammars);
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
        for (var result :
                List.of(
                        bridge.infer("prompt", "veto-output"),
                        bridge.inferWithGrammar("prompt", "root ::= \"x\""))) {
            var failure =
                    assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() == callbackFailure);
        }
    }

    @Test
    void interruptedModelRestartDoesNotLaunchAReplacement() {
        var bridge =
                spy(
                        new LlamaCppBridge(
                                mock(SlmConfiguration.class), mock(GBNFGrammarEngine.class)));
        Thread.currentThread().interrupt();
        try {
            assertFalse(bridge.restartWithModel("replacement.gguf"));
            verify(bridge, never()).start();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted(), "test must clear its retained cancellation signal");
        }
    }
}
