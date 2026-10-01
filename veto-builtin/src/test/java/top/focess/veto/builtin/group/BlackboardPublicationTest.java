package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class BlackboardPublicationTest {
    @Test
    void concurrentPublicationNeverExposesASequenceHole() throws Exception {
        var board = new Blackboard();
        var group = UUID.randomUUID();
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int publisher = 0; publisher < 8; publisher++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    for (int i = 0; i < 128; i++) {
                                        board.post(message(group));
                                        assertOrdered(board.readAll(group));
                                    }
                                    return true;
                                }));
            }
            start.countDown();
            for (var future : futures) assertEquals(true, future.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1024, board.size(group));
        assertOrdered(board.readAll(group));
    }

    @Test
    void retryKeepsOriginalSequenceAndReadsAreImmutableSnapshots() {
        var board = new Blackboard();
        var group = UUID.randomUUID();
        var message = message(group);
        var original = board.post(message);
        var snapshot = board.readAll(group);
        assertEquals(original, board.post(message));
        assertEquals(1, board.size(group));
        board.post(message(group));
        assertEquals(List.of(original), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(original));
        assertEquals(2, board.size(group));
    }

    private static @NonNull BlackboardMessage message(@NonNull UUID group) {
        return new BlackboardMessage(
                UUID.randomUUID().toString(),
                group,
                "LEADER",
                "mate",
                BlackboardMessage.MessageType.TASK_DISPATCH,
                "n:work",
                0,
                UUID.randomUUID().toString());
    }

    private static void assertOrdered(@NonNull List<BlackboardMessage> messages) {
        for (int i = 0; i < messages.size(); i++) assertEquals(i + 1L, messages.get(i).turnSeq());
    }
}
