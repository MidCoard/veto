package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameTransport;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.Version;
import top.focess.veto.terminal.client.ClientSession;
import top.focess.veto.terminal.client.ClientView;
import top.focess.veto.terminal.client.StyledText;

class TerminalInteractionOrderingTest {
    @Test
    @Timeout(10)
    void acceptedInputEntersProtocolQueueBeforeCompletionDispatchesNextCommand() throws Exception {
        var peer = new Peer();
        try (var client = new ProtocolClient(peer)) {
            var terminal = new VetoTerminal(MordantTerminal.create(), client);
            var replySelected = new CountDownLatch(1);
            var releaseReply = new CountDownLatch(1);
            var pauseReply = new AtomicBoolean();
            var failure = new AtomicReference<Throwable>();
            var session =
                    new ClientSession(
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
                                public void onTerminate(@NonNull StyledText content) {}

                                @Override
                                public void onRunning() {
                                    if (!pauseReply.compareAndSet(true, false)) return;
                                    replySelected.countDown();
                                    try {
                                        if (!releaseReply.await(3, TimeUnit.SECONDS))
                                            throw new AssertionError("Input release timed out");
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(interrupted);
                                    }
                                }
                            },
                            client::nextSequence);
            var sessionField = VetoTerminal.class.getDeclaredField("session");
            sessionField.setAccessible(true);
            sessionField.set(terminal, session);
            terminal.submitInput("first", null);
            assertEquals(
                    new Frame.Request(2, null, null, "first"), peer.sent.poll(2, TimeUnit.SECONDS));
            terminal.submitInput("second", null);
            var prompt = new Frame.Prompt("Password:", true, 2);
            terminal.acceptFrame(prompt);
            pauseReply.set(true);
            var submitting =
                    new Thread(
                            () -> {
                                try {
                                    terminal.submitInput("synthetic-secret", prompt);
                                } catch (Throwable error) {
                                    failure.set(error);
                                }
                            });
            var completionStarted = new CountDownLatch(1);
            var completionProcessed = new CountDownLatch(1);
            var completing =
                    new Thread(
                            () -> {
                                completionStarted.countDown();
                                try {
                                    terminal.acceptFrame(
                                            new Frame.Done(
                                                    new Scope.GlobalScope(),
                                                    null,
                                                    -1,
                                                    null,
                                                    null,
                                                    2));
                                } catch (Throwable error) {
                                    failure.set(error);
                                } finally {
                                    completionProcessed.countDown();
                                }
                            });
            try {
                submitting.start();
                assertTrue(replySelected.await(2, TimeUnit.SECONDS));
                completing.start();
                assertTrue(completionStarted.await(2, TimeUnit.SECONDS));
                assertFalse(completionProcessed.await(100, TimeUnit.MILLISECONDS));
                assertNull(peer.sent.poll());
            } finally {
                releaseReply.countDown();
                submitting.join(2_000);
                completing.join(2_000);
            }
            assertFalse(submitting.isAlive());
            assertFalse(completing.isAlive());
            assertNull(failure.get());
            assertEquals(
                    new Frame.Input("synthetic-secret", 2), peer.sent.poll(2, TimeUnit.SECONDS));
            assertEquals(
                    new Frame.Request(3, null, null, "second"),
                    peer.sent.poll(2, TimeUnit.SECONDS));
        }
    }

    private static final class Peer implements FrameTransport {
        private final @NonNull LinkedBlockingQueue<@NonNull Optional<@NonNull Frame>> incoming =
                new LinkedBlockingQueue<>();
        private final @NonNull LinkedBlockingQueue<Frame.@NonNull ClientFrame> sent =
                new LinkedBlockingQueue<>();
        private boolean closed;

        @Override
        public synchronized void send(@NonNull Frame frame) throws IOException {
            if (closed) throw new IOException("Peer closed");
            if (frame instanceof Frame.Hello hello) {
                if (!incoming.offer(
                        Optional.of(
                                new Frame.Welcome(
                                        Frame.PROTOCOL_VERSION, hello.seq(), Version.UNKNOWN))))
                    throw new AssertionError("Cannot queue handshake");
            } else if (frame instanceof Frame.ClientFrame clientFrame) {
                if (!sent.offer(clientFrame)) throw new AssertionError("Cannot record sent frame");
            } else throw new AssertionError("Expected client frame");
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
