package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameTransport;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.Version;
import top.focess.veto.terminal.client.ClientSession;
import top.focess.veto.terminal.client.ClientView;
import top.focess.veto.terminal.client.StyledText;

class TerminalConnectionLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(10)
    void closedConnectionTerminatesVirtualConsumerWithoutInterruption(boolean alreadyClosed)
            throws Exception {
        var client = new ProtocolClient(Peer::new);
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
