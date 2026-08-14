package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertInstanceOf;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zeromq.ZContext;

/** Round-trip tests for {@link ZmqChannel} over a real loopback ZMQ ROUTER/DEALER pair. */
class ZmqChannelRoundTripTest {

    private static final @NonNull String ADDR = "tcp://127.0.0.1:15581";

    private ZContext ctx;
    private ZmqChannel.Server router;
    private ZmqChannel.Client dealer;

    @BeforeEach
    void setUp() {
        @NonNull ZContext newContext = new ZContext();
        ctx = newContext;
        router = ZmqChannel.Server.bindRouter(newContext, ADDR);
        dealer = ZmqChannel.Client.connectDealer(newContext, ADDR, UUID.randomUUID().toString());
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
    void deltaAndDoneRoundTripOverZmq() {
        String usage =
                "/pattern create <name> <provider> <model> [sysprompt] — Create a pattern\n"
                        + "/pattern list — List your patterns\n"
                        + "/pattern use <name> — Activate a pattern";

        // 1. DEALER sends Hello so the ROUTER learns its identity.
        ZmqChannel.@NonNull Client activeDealer = dealer();
        ZmqChannel.@NonNull Server activeRouter = router();
        String userDir = System.getProperty("user.dir");
        if (userDir == null) {
            throw new AssertionError("user.dir system property is unavailable");
        }
        activeDealer.send(
                new IpcFrame.Hello(IpcFrame.PROTOCOL_VERSION, 1L, Version.UNKNOWN, userDir));

        // 2. ROUTER receives Hello, capturing the DEALER identity.
        Transport.FramedMsg hello = activeRouter.recv(2_000);
        if (hello == null) {
            throw new AssertionError("ROUTER should receive Hello");
        }
        assertInstanceOf(IpcFrame.Hello.class, hello.frame());
        String dealerId = hello.identity();
        assertFalse(dealerId.isEmpty(), "ROUTER must populate the sender identity");

        // 3. ROUTER sends Delta then Done to that identity.
        activeRouter.send(dealerId, new IpcFrame.Delta(usage));
        activeRouter.send(dealerId, new IpcFrame.Done(Map.of(), null));

        // 4. DEALER receives both, in order.
        Transport.FramedMsg f1 = activeDealer.recv(2_000);
        if (f1 == null) {
            throw new AssertionError("DEALER should receive Delta");
        }
        assertInstanceOf(IpcFrame.Delta.class, f1.frame());
        assertEquals(usage, ((IpcFrame.Delta) f1.frame()).content());
        // Client-side recv carries an empty identity (DEALER strips it).
        assertEquals("", f1.identity());

        Transport.FramedMsg f2 = activeDealer.recv(2_000);
        if (f2 == null) {
            throw new AssertionError("DEALER should receive Done");
        }
        assertInstanceOf(IpcFrame.Done.class, f2.frame());
    }

    @Test
    void nonBlockingRecvReturnsNullWhenEmpty() {
        // No frames sent: a non-blocking recv must return null immediately.
        assertNull(router().recv(0));
        assertNull(dealer().recv(0));
    }

    @Test
    void inputFrameToStringIsMasked() {
        IpcFrame.Input input = new IpcFrame.Input("my-secret-key-123");
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
