package top.focess.veto.transport.zmq;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.transport.zmq.ZmqTestSupport.assertInstanceOf;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQException;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.contract.Version;

/** Round-trip tests for {@link ZmqChannel} over a real loopback ZMQ ROUTER/DEALER pair. */
class ZmqChannelRoundTripTest {
    @Test
    void routerReportsUnknownRecipient() {
        assertThrows(
                RuntimeException.class, () -> router().send("absent-peer", new Frame.Done(null)));
    }

    @Test
    void fullPeerQueueFailsWithoutDroppingAcceptedFramesOrBlockingHealthyPeer() {
        try (var isolated = new ZContext()) {
            String address = "inproc://backpressure-" + UUID.randomUUID();
            try (var server = ZmqChannel.Server.bindRouter(isolated, address);
                    var slow = connectPeer(isolated, address, "slow");
                    var healthy = connectPeer(isolated, address, "healthy")) {
                assertTrue(slow.send(FrameCodec.encode(new Frame.Heartbeat())));
                assertTrue(server.recv(1000) != null);
                assertTrue(healthy.send(FrameCodec.encode(new Frame.Heartbeat())));
                assertTrue(server.recv(1000) != null);
                int accepted = 0;
                boolean rejected = false;
                for (int i = 0; i < 3000; i++) {
                    try {
                        server.send("slow", new Frame.Done(Integer.toString(i)));
                        accepted++;
                    } catch (RuntimeException failure) {
                        rejected = true;
                        break;
                    }
                }
                assertTrue(rejected, "A saturated peer must fail admission");
                var done = new Frame.Done("healthy");
                server.send("healthy", done);
                assertEquals(
                        done,
                        FrameCodec.decode(
                                Objects.requireNonNull(
                                        healthy.recv(), "Missing healthy peer frame")));
                for (int i = 0; i < accepted; i++)
                    assertEquals(
                            new Frame.Done(Integer.toString(i)),
                            FrameCodec.decode(
                                    Objects.requireNonNull(slow.recv(), "Missing accepted frame")));
                assertNull(slow.recv(ZMQ.DONTWAIT));
            }
        }
    }

    private static ZMQ.@NonNull Socket connectPeer(
            @NonNull ZContext context, @NonNull String address, @NonNull String identity) {
        var socket = context.createSocket(SocketType.DEALER);
        assertTrue(socket.setIdentity(identity.getBytes(ZMQ.CHARSET)));
        assertTrue(socket.setReceiveTimeOut(1000));
        assertTrue(socket.connect(address));
        return socket;
    }

    private static final @NonNull String ADDR = "tcp://127.0.0.1:15581";

    private ZContext ctx;
    private ZmqChannel.Server router;
    private ZmqChannel.Client dealer;

    @BeforeEach
    void setUp() {
        ZContext newContext = new ZContext();
        ctx = newContext;
        router = ZmqChannel.Server.bindRouter(newContext, ADDR);
        dealer = ZmqChannel.Client.connect(ADDR);
    }

    @AfterEach
    void tearDown() {
        dealer().close();
        router().close();
        context().close();
    }

    private @NonNull ZContext context() {
        if (ctx == null) {
            throw new AssertionError("ZMQ context was not initialized");
        }
        return ctx;
    }

    private ZmqChannel.@NonNull Server router() {
        if (router == null) {
            throw new AssertionError("ROUTER channel was not initialized");
        }
        return router;
    }

    private ZmqChannel.@NonNull Client dealer() {
        if (dealer == null) {
            throw new AssertionError("DEALER channel was not initialized");
        }
        return dealer;
    }

    @Test
    void eventAndDoneRoundTripOverZmq() {
        String usage =
                "/pattern create <name> <provider> <model> [sysprompt] — Create a pattern\n"
                        + "/pattern list — List your patterns\n"
                        + "/pattern use <name> — Activate a pattern";

        // 1. DEALER sends Hello so the ROUTER learns its identity.
        ZmqChannel.Client activeDealer = dealer();
        ZmqChannel.Server activeRouter = router();
        String userDir = System.getProperty("user.dir");
        if (userDir == null) {
            throw new AssertionError("user.dir system property is unavailable");
        }
        activeDealer.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1L, Version.UNKNOWN, userDir));

        // 2. ROUTER receives Hello, capturing the DEALER identity.
        ServerTransport.Message hello = activeRouter.recv(2_000);
        if (hello == null) {
            throw new AssertionError("ROUTER should receive Hello");
        }
        assertInstanceOf(Frame.Hello.class, hello.frame());
        String dealerId = hello.identity();
        assertFalse(dealerId.isEmpty(), "ROUTER must populate the sender identity");

        // 3. ROUTER sends Delta then Done to that identity.
        var event =
                new Delta(
                        new Scope.AgentScope(
                                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                (UUID.randomUUID()).toString(),
                                "agent"),
                        17,
                        Instant.now(),
                        new Delta.ToolCall(null, usage, JsonNodeFactory.instance.objectNode(), 0));
        activeRouter.send(dealerId, event);
        activeRouter.send(dealerId, new Frame.Done(null));

        // 4. DEALER receives both, in order.
        Frame f1 = activeDealer.recv(2_000);
        if (f1 == null) {
            throw new AssertionError("DEALER should receive Delta");
        }
        assertInstanceOf(Delta.class, f1);
        assertEquals(event, f1);

        Frame f2 = activeDealer.recv(2_000);
        if (f2 == null) {
            throw new AssertionError("DEALER should receive Done");
        }
        assertInstanceOf(Frame.Done.class, f2);
    }

    @Test
    void nonBlockingRecvReturnsNullWhenEmpty() {
        // No frames sent: a non-blocking recv must return null immediately.
        assertNull(router().recv(0));
        assertNull(dealer().recv(0));
    }

    @Test
    void rejectsExtraMultipartPartsAndCarriesRequestsInBothDirections() {
        assertTrue(dealer().socket.sendMore("extra"));
        assertTrue(dealer().socket.send(FrameCodec.encode(new Frame.Heartbeat())));
        assertNull(router().recv(2_000));
        dealer().send(new Frame.Hint("command", 2));
        var message = router().recv(2_000);
        if (message == null) throw new AssertionError("Missing client request");
        assertEquals(new Frame.Hint("command", 2), message.frame());
        var scope = new Scope.AgentScope(UUID.randomUUID(), UUID.randomUUID().toString(), "agent");
        var request =
                new Frame.Veto(
                        scope,
                        "call",
                        "tool",
                        "GENERIC",
                        List.of("DECLINE"),
                        Map.of(),
                        null,
                        null,
                        2);
        router().send(message.identity(), request);
        assertEquals(request, dealer().recv(2_000));
        var reply = new Frame.VetoResult(scope, "call", "DECLINE", 2);
        dealer().send(reply);
        var response = router().recv(2_000);
        if (response == null) throw new AssertionError("Missing client response");
        assertEquals(reply, response.frame());
        var hint = new Frame.HintResult(new Frame.HintInfo("argument", null), 2);
        router().send(message.identity(), hint);
        assertEquals(hint, dealer().recv(2_000));
    }

    @Test
    void rejectsFramesSentInTheWrongDirection() {
        assertTrue(dealer().socket.send(FrameCodec.encode(new Frame.Done(null))));
        assertNull(router().recv(2_000));
        dealer().send(new Frame.Heartbeat());
        var message = router().recv(2_000);
        if (message == null) throw new AssertionError("Missing heartbeat");
        assertTrue(router().socket.sendMore(message.identity()));
        assertTrue(router().socket.send(FrameCodec.encode(new Frame.Request("wrong direction"))));
        assertNull(dealer().recv(2_000));
        var ack = new Frame.HeartbeatAck(Instant.now());
        router().send(message.identity(), ack);
        assertEquals(ack, dealer().recv(2_000));
    }

    @Test
    void invalidClientAddressFailsWithoutAffectingTheServerContext() {
        int before = context().getSockets().size();
        assertThrows(ZMQException.class, () -> ZmqChannel.Client.connect("invalid://address"));
        assertEquals(before, context().getSockets().size());
        assertFalse(context().isClosed());
    }

    @Test
    void clientClosesItsOwnContextAndServerLeavesItsContextToTheCaller() throws Exception {
        var field = ZmqChannel.Client.class.getDeclaredField("context");
        field.setAccessible(true);
        if (!(field.get(dealer()) instanceof ZContext clientContext))
            throw new AssertionError("Missing client context");
        assertFalse(clientContext.isClosed());
        dealer().close();
        dealer().close();
        assertTrue(clientContext.isClosed());
        assertFalse(context().isClosed());
        router().close();
        assertFalse(context().isClosed());
    }

    @Test
    void inputFrameToStringIsMasked() {
        Frame.Input input = new Frame.Input("my-secret-key-123");
        String str = input.toString();
        assertFalse(str.contains("my-secret-key-123"));
        assertTrue(str.contains("********"));
    }

    @Test
    void closeIsSafeToCallRepeatedly() {
        // Single-threaded contract: close is called by the owning thread. There is no liveness
        // flag to assert; we verify close() is safe to repeat and the channel is simply done.
        assertDoesNotThrow(
                () -> {
                    router().close();
                    router().close();
                });
    }
}
