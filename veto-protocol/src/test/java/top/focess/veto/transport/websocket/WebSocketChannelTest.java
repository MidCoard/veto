package top.focess.veto.transport.websocket;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.contract.EventFrame;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.Version;

class WebSocketChannelTest {
    @Test
    void directionIdentityAndMalformedFramesAreChecked() {
        var client = new WebSocketChannel.Client(text -> {}, () -> {});
        var server = new WebSocketChannel.Server("peer", text -> {}, () -> {});
        assertFalse(client.accept(FrameCodec.encodeString(new Frame.Heartbeat(1))));
        assertFalse(
                server.accept(FrameCodec.encodeString(new Frame.HeartbeatAck(1, Instant.now()))));
        assertFalse(server.accept("not-json"));
        assertTrue(server.accept(FrameCodec.encodeString(new Frame.Heartbeat(2))));
        var received = server.recv(0);
        if (received == null) throw new AssertionError("Missing heartbeat");
        assertEquals("peer", received.identity());
        assertEquals(new Frame.Heartbeat(2), received.frame());
        assertNull(server.recv(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> server.send("other", new Frame.Unsubscribe()));
        assertThrows(
                IllegalArgumentException.class, () -> server.send("peer", new Frame.Unsubscribe()));
        client.close();
        server.close();
    }

    @Test
    @Timeout(5)
    void closeWakesBlockedReceiverAndClosesOnlyOnce() throws Exception {
        var started = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var closes = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();
        var channel = new WebSocketChannel.Client(text -> {}, closes::countDown);
        Thread receiver =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    started.countDown();
                                    try {
                                        assertThrows(
                                                IllegalStateException.class,
                                                () -> channel.recv(-1));
                                    } catch (Throwable failed) {
                                        failure.set(failed);
                                    } finally {
                                        finished.countDown();
                                    }
                                });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        channel.close();
        channel.close();
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        receiver.join();
        assertNull(failure.get());
        assertEquals(1, closes.getCount());
        assertFalse(channel.accept(FrameCodec.encodeString(new Frame.Error("late", 0))));
    }

    @Test
    @Timeout(5)
    void commonClientHandshakesAndRoutesOverWebSocket() throws Exception {
        var incoming = new AtomicReference<WebSocketChannel.Client>();
        var peer =
                new WebSocketChannel.Server(
                        "peer",
                        text -> {
                            var client = incoming.get();
                            if (client == null || !client.accept(text))
                                throw new AssertionError("Invalid server frame");
                        },
                        () -> {});
        var channel =
                new WebSocketChannel.Client(
                        text -> {
                            assertTrue(peer.accept(text));
                            var received = peer.recv(0);
                            if (received == null) throw new AssertionError("Missing client frame");
                            switch (received.frame()) {
                                case Frame.Hello hello ->
                                        peer.send(
                                                "peer",
                                                new Frame.Welcome(
                                                        Frame.PROTOCOL_VERSION,
                                                        hello.seq(),
                                                        Version.parse("1.0.100")));
                                case Frame.Subscribe subscribe ->
                                        peer.send(
                                                "peer", new Frame.Subscribed("all", Instant.now()));
                                case Frame.Bye ignored -> peer.close();
                                default -> throw new AssertionError("Unexpected client frame");
                            }
                        },
                        peer::close);
        incoming.set(channel);
        assertTrue(channel.accept(FrameCodec.encodeString(EventFrame.command("early event"))));
        try (var client = new ProtocolClient(channel)) {
            assertTrue(
                    client.receive(1, TimeUnit.SECONDS) instanceof EventFrame event
                            && event.text().equals("early event"));
            assertEquals(Frame.PROTOCOL_VERSION, client.negotiatedVersion());
            assertEquals(Version.parse("1.0.100"), client.serverProductVersion());
            client.send(new Frame.Subscribe("all"));
            assertInstanceOf(Frame.Subscribed.class, client.receive(1, TimeUnit.SECONDS));
        }
        assertThrows(IllegalStateException.class, () -> channel.recv(0));
    }

    @Test
    @Timeout(5)
    void sendFailureTerminatesTheCommonClientAndClosesSocket() throws Exception {
        var incoming = new AtomicReference<WebSocketChannel.Client>();
        var closed = new CountDownLatch(1);
        var channel =
                new WebSocketChannel.Client(
                        text -> {
                            if (FrameCodec.decode(text) instanceof Frame.Hello hello) {
                                var target = incoming.get();
                                if (target == null
                                        || !target.accept(
                                                FrameCodec.encodeString(
                                                        new Frame.Welcome(
                                                                Frame.PROTOCOL_VERSION,
                                                                hello.seq(),
                                                                Version.UNKNOWN))))
                                    throw new AssertionError("Handshake response was rejected");
                            } else throw new IOException("Socket send failed");
                        },
                        closed::countDown);
        incoming.set(channel);
        try (var client = new ProtocolClient(channel)) {
            client.send(new Frame.Subscribe("all"));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertTrue(client.isClosed());
        }
    }

    @Test
    void overflowClosesInsteadOfSilentlyDroppingFrames() {
        var channel = new WebSocketChannel.Client(text -> {}, () -> {});
        String text = FrameCodec.encodeString(new Frame.Error("event", 0));
        for (int i = 0; i < 256; i++) assertTrue(channel.accept(text));
        assertThrows(IllegalStateException.class, () -> channel.accept(text));
        assertThrows(IllegalStateException.class, () -> channel.recv(0));
    }
}
