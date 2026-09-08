package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BlackboardWaitTest {
    @Test
    void messageWakesReaderWithoutLosingNotification() throws Exception {
        var blackboard = new Blackboard();
        var groupId = UUID.randomUUID();
        var checked = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting =
                    executor.submit(
                            () -> {
                                blackboard.awaitChange(
                                        () -> {
                                            checked.countDown();
                                            return blackboard.size(groupId) > 0;
                                        },
                                        TimeUnit.SECONDS.toNanos(30));
                                return blackboard.size(groupId);
                            });
            try {
                assertTrue(checked.await(5, TimeUnit.SECONDS));
                GroupTestMessages.accept(blackboard, groupId, "mate", "node");
                assertEquals(1, waiting.get(5, TimeUnit.SECONDS));
            } finally {
                waiting.cancel(true);
            }
        }
    }

    @Test
    void disbandWakesReaderWithoutAMessage() throws Exception {
        var blackboard = new Blackboard();
        var registry = new GroupRegistry();
        var group =
                Group.create(
                        "leader",
                        "user",
                        "brief",
                        blackboard,
                        new ExecutionDag(UUID.randomUUID(), List.of()));
        registry.put(group);
        var checked = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting =
                    executor.submit(
                            () -> {
                                blackboard.awaitChange(
                                        () -> {
                                            checked.countDown();
                                            var current = registry.get(group.groupId());
                                            return current == null
                                                    || current.state() != Group.GroupState.ACTIVE;
                                        },
                                        TimeUnit.SECONDS.toNanos(30));
                                return true;
                            });
            try {
                assertTrue(checked.await(5, TimeUnit.SECONDS));
                registry.disband(group.groupId(), Instant.now());
                assertTrue(waiting.get(5, TimeUnit.SECONDS));
                assertEquals(0, blackboard.size(group.groupId()));
            } finally {
                waiting.cancel(true);
            }
        }
    }

    @Test
    void waitCanBeInterruptedAndZeroTimeoutReturnsImmediately() throws Exception {
        var blackboard = new Blackboard();
        blackboard.awaitChange(() -> false, 0);
        var checked = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        Thread reader =
                Thread.startVirtualThread(
                        () -> {
                            try {
                                blackboard.awaitChange(
                                        () -> {
                                            checked.countDown();
                                            return false;
                                        },
                                        TimeUnit.SECONDS.toNanos(30));
                            } catch (InterruptedException e) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                        });
        try {
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            reader.interrupt();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally {
            reader.interrupt();
            reader.join(5_000);
        }
    }
}
