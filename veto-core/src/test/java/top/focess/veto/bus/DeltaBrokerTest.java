package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Delta;

/** Tests for the Delta broker. */
class DeltaBrokerTest {

    @Test
    void publishFansOutToSubscribers() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        UUID sessionId = UUID.randomUUID();
        List<Delta> received1 = new CopyOnWriteArrayList<>();
        List<Delta> received2 = new CopyOnWriteArrayList<>();
        try (AutoCloseable s1 =
                        broker.subscribe(
                                sessionId,
                                frame -> {
                                    if (frame instanceof Delta delta) received1.add(delta);
                                });
                AutoCloseable s2 =
                        broker.subscribe(
                                sessionId,
                                frame -> {
                                    if (frame instanceof Delta delta) received2.add(delta);
                                })) {
            assertNotNull(s1);
            assertNotNull(s2);
            broker.publish(
                    new Delta(
                            new Scope.SessionScope(
                                    UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                    sessionId.toString()),
                            new Delta.Message("hello", 0)));
            broker.publish(
                    new Delta(
                            new Scope.SessionScope(
                                    UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                    sessionId.toString()),
                            new Delta.Thought("thinking...", 0)));
            assertEquals(2, received1.size());
            assertEquals(2, received2.size());
            assertEquals("hello", ((Delta.Message) received1.get(0).data()).text());
            assertEquals(1L, received1.get(0).sequence());
            assertEquals(2L, received1.get(1).sequence());
        }
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        UUID sessionId = UUID.randomUUID();
        List<Delta> received = new CopyOnWriteArrayList<>();
        AutoCloseable handle =
                broker.subscribe(
                        sessionId,
                        frame -> {
                            if (frame instanceof Delta delta) received.add(delta);
                        });
        broker.publish(
                new Delta(
                        new Scope.SessionScope(
                                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                sessionId.toString()),
                        new Delta.Message("a", 0)));
        assertEquals(1, received.size());
        // Unsubscribe.
        handle.close();
        broker.publish(
                new Delta(
                        new Scope.SessionScope(
                                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                sessionId.toString()),
                        new Delta.Message("b", 0)));
        // The unsubscribed listener should not have received the second frame.
        assertEquals(1, received.size());
    }

    @Test
    void sequencesAreMonotonicPerSession() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        UUID sessionId = UUID.randomUUID();
        List<Delta> received = new CopyOnWriteArrayList<>();
        try (AutoCloseable s =
                broker.subscribe(
                        sessionId,
                        frame -> {
                            if (frame instanceof Delta delta) received.add(delta);
                        })) {
            assertNotNull(s);
            for (int i = 0; i < 10; i++) {
                broker.publish(
                        new Delta(
                                new Scope.SessionScope(
                                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                        sessionId.toString()),
                                new Delta.Thought("step " + i, 0)));
            }
        }
        assertEquals(10, received.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1L, received.get(i).sequence());
        }
    }
}
