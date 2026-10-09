package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import top.focess.veto.contract.EventFrame;

/** Tests for the EventFrame broker. */
class DeltaBrokerTest {

    @Test
    void publishFansOutToSubscribers() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        UUID sessionId = UUID.randomUUID();
        List<EventFrame> received1 = new CopyOnWriteArrayList<>();
        List<EventFrame> received2 = new CopyOnWriteArrayList<>();
        try (AutoCloseable s1 = broker.subscribe(sessionId, received1::add);
                AutoCloseable s2 = broker.subscribe(sessionId, received2::add)) {
            assertNotNull(s1);
            assertNotNull(s2);
            broker.publish(
                    EventFrame.builder()
                            .sessionId(sessionId)
                            .kind(EventFrame.Kind.ASSISTANT_MESSAGE)
                            .text("hello")
                            .build());
            broker.publish(
                    EventFrame.builder()
                            .sessionId(sessionId)
                            .kind(EventFrame.Kind.ASSISTANT_THOUGHT)
                            .text("thinking...")
                            .build());
            assertEquals(2, received1.size());
            assertEquals(2, received2.size());
            assertEquals("hello", received1.get(0).text());
            assertEquals(1L, received1.get(0).sequence());
            assertEquals(2L, received1.get(1).sequence());
        }
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        UUID sessionId = UUID.randomUUID();
        List<EventFrame> received = new CopyOnWriteArrayList<>();
        AutoCloseable handle = broker.subscribe(sessionId, received::add);
        broker.publish(
                EventFrame.builder()
                        .sessionId(sessionId)
                        .kind(EventFrame.Kind.ASSISTANT_MESSAGE)
                        .text("a")
                        .build());
        assertEquals(1, received.size());
        // Unsubscribe.
        handle.close();
        broker.publish(
                EventFrame.builder()
                        .sessionId(sessionId)
                        .kind(EventFrame.Kind.ASSISTANT_MESSAGE)
                        .text("b")
                        .build());
        // The unsubscribed listener should not have received the second frame.
        assertEquals(1, received.size());
    }

    @Test
    void sequencesAreMonotonicPerSession() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        UUID sessionId = UUID.randomUUID();
        List<EventFrame> received = new CopyOnWriteArrayList<>();
        try (AutoCloseable s = broker.subscribe(sessionId, received::add)) {
            assertNotNull(s);
            for (int i = 0; i < 10; i++) {
                broker.publish(
                        EventFrame.builder()
                                .sessionId(sessionId)
                                .kind(EventFrame.Kind.ASSISTANT_THOUGHT)
                                .text("step " + i)
                                .build());
            }
        }
        assertEquals(10, received.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1L, received.get(i).sequence());
        }
    }
}
