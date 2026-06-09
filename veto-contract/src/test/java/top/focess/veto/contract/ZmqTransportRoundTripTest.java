package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zeromq.ZContext;

class ZmqTransportRoundTripTest {

    private static final String ADDR = "tcp://127.0.0.1:15580";

    private ZContext ctx;
    private ZmqTransport router;
    private ZmqTransport dealer;

    @BeforeEach
    void setUp() {
        ctx = new ZContext();
        router = ZmqTransport.bindRouter(ctx, ADDR);
        dealer = ZmqTransport.connectDealer(ctx, ADDR, UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        dealer.close();
        router.close();
        ctx.close();
    }

    @Test
    void deltaAndDoneRoundTripOverZMQ() throws Exception {
        String usage =
                "/pattern create <name> <provider> <model> [sysprompt] — Create a pattern\n"
                        + "/pattern list — List your patterns\n"
                        + "/pattern use <name> — Activate a pattern";

        // 1. DEALER sends Hello so ROUTER learns its identity
        dealer.send(new IpcFrame.Hello(1));

        // 2. ROUTER receives Hello, gets DEALER identity
        String[] routerRecv = router.tryReceive();
        assertNotNull(routerRecv, "ROUTER should receive Hello");
        String dealerId = routerRecv[0];

        // 3. ROUTER sends Delta then Done
        IpcFrame delta = new IpcFrame.Delta(usage, 0);
        IpcFrame done = new IpcFrame.Done(Map.of(), null, 1);
        router.send(dealerId, delta);
        router.send(dealerId, done);
        Thread.sleep(100);

        // 4. DEALER receives via ZmqTransport (uses explicit UTF-8 decode)
        String[] parts1 = dealer.tryReceive();
        assertNotNull(parts1, "dealer.tryReceive() returned null for Delta");
        IpcFrame received1 = ZmqTransport.deserialize(parts1[1]);
        assertNotNull(received1, "Delta deserialization returned null");
        assertInstanceOf(IpcFrame.Delta.class, received1);
        assertEquals(usage, ((IpcFrame.Delta) received1).content());

        String[] parts2 = dealer.tryReceive();
        assertNotNull(parts2, "dealer.tryReceive() returned null for Done");
        IpcFrame received2 = ZmqTransport.deserialize(parts2[1]);
        assertNotNull(received2, "Done deserialization returned null");
        assertInstanceOf(IpcFrame.Done.class, received2);
    }
}
