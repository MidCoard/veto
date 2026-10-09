package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.contract.ClientTransport;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.Version;
import top.focess.veto.terminal.client.ClientSession;
import top.focess.veto.terminal.client.ClientView;
import top.focess.veto.terminal.client.StyledText;

class TerminalConnectionLifecycleTest {
    @Test
    @Timeout(10)
    void closedConnectionTerminatesVirtualConsumerInsteadOfSpinning() throws Exception {
        var client = new ProtocolClient(Peer::new);
        try {
            client.close();
            var terminal = new VetoTerminal(MordantTerminal.create(), client);
            var terminations = new AtomicInteger();
            var view = new ClientView() {
                @Override
                public void onDelta(@NonNull String content) {}
                @Override
                public void onProgress(@NonNull StyledText content) {}
                @Override
                public void onPrompt(Frame.@NonNull Prompt prompt) {}
                @Override
                public void onError(@NonNull StyledText content) {}
                @Override
                public void onTerminate(@NonNull StyledText content) {
                    terminations.incrementAndGet();
                }
            };
            var session = VetoTerminal.class.getDeclaredField("session");
            session.setAccessible(true);
            session.set(terminal, new ClientSession(view));
            var running = VetoTerminal.class.getDeclaredField("running");
            running.setAccessible(true);
            running.setBoolean(terminal, true);
            var factory = VetoTerminal.class.getDeclaredMethod("createConsumerThread");
            factory.setAccessible(true);
            var consumer = (Thread) factory.invoke(terminal);
            if (consumer == null) throw new AssertionError("Missing terminal consumer");
            assertTrue(consumer.isVirtual());
            consumer.start();
            consumer.join(1_000);
            assertFalse(consumer.isAlive());
            assertEquals(1, terminations.get());
        } finally {
            client.close();
        }
    }

    private static final class Peer implements ClientTransport {
        private final @NonNull LinkedBlockingQueue<Frame.@NonNull ServerFrame> incoming = new LinkedBlockingQueue<>();
        @Override
        public void send(Frame.@NonNull ClientFrame frame) {
            if (frame instanceof Frame.Hello hello && !incoming.offer(new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), Version.UNKNOWN)))
                throw new AssertionError("Cannot queue handshake");
        }
        @Override
        public Frame.ServerFrame recv(long timeoutMillis) {
            try {
                return incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        @Override
        public void close() {}
    }
}
