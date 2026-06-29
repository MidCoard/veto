package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Tests for the DeltaFrame broker. */
class DeltaBrokerTest {

    @Test
    void publishFansOutToSubscribers() throws Exception {
        DeltaBroker broker = new DeltaBroker(new ObjectMapper());
        UUID sessionId = UUID.randomUUID();
        List<DeltaFrame> received1 = new CopyOnWriteArrayList<>();
        List<DeltaFrame> received2 = new CopyOnWriteArrayList<>();
        try (AutoCloseable s1 = broker.subscribe(sessionId, received1::add);
                AutoCloseable s2 = broker.subscribe(sessionId, received2::add)) {
            broker.publish(
                    DeltaFrame.builder()
                            .sessionId(sessionId)
                            .kind(DeltaFrame.Kind.ASSISTANT_MESSAGE)
                            .text("hello")
                            .build());
            broker.publish(
                    DeltaFrame.builder()
                            .sessionId(sessionId)
                            .kind(DeltaFrame.Kind.ASSISTANT_THOUGHT)
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
        DeltaBroker broker = new DeltaBroker(new ObjectMapper());
        UUID sessionId = UUID.randomUUID();
        List<DeltaFrame> received = new CopyOnWriteArrayList<>();
        AutoCloseable handle = broker.subscribe(sessionId, received::add);
        broker.publish(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.ASSISTANT_MESSAGE)
                        .text("a")
                        .build());
        assertEquals(1, received.size());
        // Unsubscribe.
        handle.close();
        broker.publish(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.ASSISTANT_MESSAGE)
                        .text("b")
                        .build());
        // The unsubscribed listener should not have received the second frame.
        assertEquals(1, received.size());
    }

    @Test
    void sequencesAreMonotonicPerSession() throws Exception {
        DeltaBroker broker = new DeltaBroker(new ObjectMapper());
        UUID sessionId = UUID.randomUUID();
        List<DeltaFrame> received = new CopyOnWriteArrayList<>();
        try (AutoCloseable s = broker.subscribe(sessionId, received::add)) {
            for (int i = 0; i < 10; i++) {
                broker.publish(
                        DeltaFrame.builder()
                                .sessionId(sessionId)
                                .kind(DeltaFrame.Kind.ASSISTANT_THOUGHT)
                                .text("step " + i)
                                .build());
            }
        }
        assertEquals(10, received.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1L, received.get(i).sequence());
        }
    }

    @Test
    void deltaFrameRoundTripsJson() {
        ObjectMapper mapper = new ObjectMapper();
        DeltaFrame original =
                DeltaFrame.builder()
                        .sessionId(UUID.randomUUID())
                        .sequence(42L)
                        .kind(DeltaFrame.Kind.TOOL_RESULT)
                        .text("file contents")
                        .build();
        String json = original.toJson(mapper);
        DeltaFrame parsed = DeltaFrame.fromJson(mapper, json);
        assertEquals(original.sessionId(), parsed.sessionId());
        assertEquals(original.sequence(), parsed.sequence());
        assertEquals(original.kind(), parsed.kind());
        assertEquals(original.text(), parsed.text());
    }
}
