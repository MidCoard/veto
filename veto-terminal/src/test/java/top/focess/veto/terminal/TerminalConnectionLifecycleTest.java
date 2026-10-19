package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.api.util.Threads;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameTransport;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.Version;
import top.focess.veto.terminal.client.ClientSession;
import top.focess.veto.terminal.client.ClientView;
import top.focess.veto.terminal.client.StyledText;

class TerminalConnectionLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(10)
    void inputDuringHandshakeFailureLeavesTerminationFeedbackToConsumer(boolean cancel)
            throws Exception {
        var failConnection = new CountDownLatch(1);
        var terminationQueued = new CountDownLatch(1);
        var releaseTermination = new CountDownLatch(1);
        var terminationRendered = new CountDownLatch(1);
        var inputAttempts = new AtomicInteger();
        var output = new ByteArrayOutputStream();
        try (var client =
                        new ProtocolClient(
                                new FrameTransport() {
                                    public void send(@NonNull Frame frame) {}

                                    public @NonNull Frame receive() throws IOException {
                                        try {
                                            if (!failConnection.await(3, TimeUnit.SECONDS))
                                                throw new IOException(
                                                        "Handshake failure was not released");
                                        } catch (InterruptedException interrupted) {
                                            throw new IOException(interrupted);
                                        }
                                        throw new IOException("Connection closed during handshake");
                                    }

                                    public void close() {
                                        failConnection.countDown();
                                    }
                                });
                var terminal =
                        new DumbTerminal(
                                "connection-failure-test",
                                "xterm",
                                InputStream.nullInputStream(),
                                output,
                                StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(80, 24));
            var controller =
                    new VetoTerminal(MordantTerminal.create(), client) {
                        @Override
                        void connectionClosed(@NonNull String reason) {
                            terminationQueued.countDown();
                            try {
                                if (!releaseTermination.await(3, TimeUnit.SECONDS))
                                    throw new AssertionError("Termination was not released");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                            super.connectionClosed(reason);
                            terminationRendered.countDown();
                        }
                    };
            var reader =
                    new LineReaderImpl(terminal) {
                        @Override
                        public @NonNull String readLine(String prompt, Character mask) {
                            int attempt = inputAttempts.incrementAndGet();
                            if (cancel && attempt == 1) return "/help";
                            try {
                                if (attempt == (cancel ? 2 : 1)) {
                                    failConnection.countDown();
                                    assertTrue(terminationQueued.await(3, TimeUnit.SECONDS));
                                    if (cancel) throw new UserInterruptException("");
                                    return "/help";
                                }
                                releaseTermination.countDown();
                                assertTrue(terminationRendered.await(3, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            throw new UserInterruptException("");
                        }
                    };
            var completed = new CompletableFuture<Void>();
            var repl =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            controller.start(reader);
                                            completed.complete(null);
                                        } catch (Throwable failure) {
                                            completed.completeExceptionally(failure);
                                        }
                                    });
            try {
                completed.get(5, TimeUnit.SECONDS);
            } finally {
                failConnection.countDown();
                releaseTermination.countDown();
                Threads.awaitTermination(repl, 2_000);
            }
            assertFalse(repl.isAlive());
            assertEquals(cancel ? 3 : 2, inputAttempts.get());
            assertEquals(0, terminationRendered.getCount());
            terminal.flush();
            assertTrue(
                    output.toString(StandardCharsets.UTF_8).contains("Backend connection closed."));
        }
    }

    @Test
    @Timeout(10)
    void interactionFailureStillPropagatesWhileConnectionIsOpen() throws Exception {
        try (var client = new ProtocolClient(new Peer());
                var terminal =
                        new DumbTerminal(
                                "interaction-failure-test",
                                "xterm",
                                InputStream.nullInputStream(),
                                new ByteArrayOutputStream(),
                                StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(80, 24));
            var expected = new IllegalStateException("Input handling failed");
            var reader =
                    new LineReaderImpl(terminal) {
                        @Override
                        public @NonNull String readLine(String prompt, Character mask) {
                            throw expected;
                        }
                    };
            var controller = new VetoTerminal(MordantTerminal.create(), client);
            assertSame(
                    expected,
                    assertThrows(IllegalStateException.class, () -> controller.start(reader)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(10)
    void closedConnectionTerminatesVirtualConsumerWithoutInterruption(boolean alreadyClosed)
            throws Exception {
        var client = new ProtocolClient(new Peer());
        try {
            if (alreadyClosed) client.close();
            var terminal = new VetoTerminal(MordantTerminal.create(), client);
            var terminations = new AtomicInteger();
            var view =
                    new ClientView() {
                        @Override
                        public void onDelta(@NonNull String content) {}

                        @Override
                        public void onProgress(@NonNull StyledText content) {}

                        @Override
                        public void onPrompt(Frame.@NonNull ServerFrame prompt) {}

                        @Override
                        public void onError(@NonNull StyledText content) {}

                        @Override
                        public void onTerminate(@NonNull StyledText content) {
                            terminations.incrementAndGet();
                        }
                    };
            var session = VetoTerminal.class.getDeclaredField("session");
            session.setAccessible(true);
            session.set(terminal, new ClientSession(view, client::nextSequence));
            var running = VetoTerminal.class.getDeclaredField("running");
            running.setAccessible(true);
            running.setBoolean(terminal, true);
            var consume = VetoTerminal.class.getDeclaredMethod("consumeFrames");
            consume.setAccessible(true);
            var completed = new CompletableFuture<Void>();
            var interrupted = new AtomicBoolean();
            var consumer =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            consume.invoke(terminal);
                                            interrupted.set(Thread.currentThread().isInterrupted());
                                            completed.complete(null);
                                        } catch (ReflectiveOperationException failure) {
                                            completed.completeExceptionally(failure);
                                        }
                                    });
            assertTrue(consumer.isVirtual());
            if (!alreadyClosed) {
                assertTrue(consumer.isAlive());
                client.close();
            }
            completed.get(1, TimeUnit.SECONDS);
            consumer.join(1_000);
            assertFalse(consumer.isAlive());
            assertFalse(interrupted.get());
            assertEquals(1, terminations.get());
        } finally {
            client.close();
        }
    }

    private static final class Peer implements FrameTransport {
        private final @NonNull LinkedBlockingQueue<@NonNull Optional<@NonNull Frame>> incoming =
                new LinkedBlockingQueue<>();
        private boolean closed;

        @Override
        public synchronized void send(@NonNull Frame frame) throws IOException {
            if (closed) throw new IOException("Peer closed");
            if (frame instanceof Frame.Hello hello
                    && !incoming.offer(
                            Optional.of(
                                    new Frame.Welcome(
                                            Frame.PROTOCOL_VERSION, hello.seq(), Version.UNKNOWN))))
                throw new AssertionError("Cannot queue handshake");
        }

        @Override
        public @NonNull Frame receive() throws IOException {
            try {
                return incoming.take().orElseThrow(() -> new IOException("Peer closed"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Receive interrupted", interrupted);
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (!incoming.offer(Optional.empty())) throw new AssertionError("Cannot signal close");
        }
    }
}
