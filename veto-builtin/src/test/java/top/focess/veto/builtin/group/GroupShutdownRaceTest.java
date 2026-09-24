package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.AgentConfiguration;

class GroupShutdownRaceTest {
    @Test
    @Timeout(5)
    void tickQueuedDuringDisbandCannotResurrectOrDispatchWork() throws Exception {
        var board = new Blackboard();
        var registry = new GroupRegistry();
        var group =
                Group.create(
                                "leader",
                                "owner",
                                "work",
                                board,
                                ExecutionDag.linear(UUID.randomUUID(), List.of("work")))
                        .withMate("mate", "general");
        registry.put(group);
        var orchestrator = new GroupOrchestrator(registry, board);
        var closing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ticking = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var close =
                    executor.submit(
                            () ->
                                    orchestrator.closeGroup(
                                            group.groupId(),
                                            () -> {
                                                closing.countDown();
                                                try {
                                                    assertTrue(release.await(2, TimeUnit.SECONDS));
                                                } catch (InterruptedException error) {
                                                    Thread.currentThread().interrupt();
                                                    throw new AssertionError(error);
                                                }
                                                registry.disband(group.groupId(), Instant.now());
                                            }));
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            var tick =
                    executor.submit(
                            () -> {
                                ticking.countDown();
                                return orchestrator.tick(group.groupId());
                            });
            assertTrue(ticking.await(2, TimeUnit.SECONDS));
            release.countDown();
            close.get(2, TimeUnit.SECONDS);
            tick.get(2, TimeUnit.SECONDS);
            assertEquals(
                    GroupState.DISBANDED,
                    GroupTestHost.required(registry.get(group.groupId())).state());
            assertTrue(board.readAll(group.groupId()).isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    @Timeout(6)
    void runtimeStopUsesOneDeadlineAndRetainsUnsettledMembersForRetry() throws Exception {
        var board = new Blackboard();
        var registry = new GroupRegistry();
        var group =
                Group.create(
                                "leader",
                                "owner",
                                "work",
                                board,
                                new ExecutionDag(UUID.randomUUID(), List.of()))
                        .withMate("one", "work")
                        .withMate("two", "work");
        registry.put(group);
        var first = GroupTestHost.child("one");
        var second = GroupTestHost.child("two");
        var waits = new AtomicInteger();
        for (var child : List.of(first, second)) {
            when(child.awaitTermination(any(ToolDocs.nonNullClass(Duration.class))))
                    .thenAnswer(
                            call -> {
                                Duration remaining = GroupTestHost.required(call.getArgument(0));
                                if (waits.getAndIncrement() == 0) {
                                    assertTrue(remaining.compareTo(Duration.ofSeconds(2)) <= 0);
                                    Thread.sleep(remaining.toMillis());
                                } else assertTrue(remaining.compareTo(Duration.ofMillis(200)) < 0);
                                return false;
                            });
        }
        var spawner =
                new GroupSpawner(
                        registry,
                        board,
                        (g, id, name, responsibility) -> id.equals("one") ? first : second);
        spawner.restoreMates(group);
        long started = System.nanoTime();
        assertThrows(IllegalStateException.class, () -> spawner.stopRuntime(group.groupId()));
        assertTrue(
                Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        for (var child : List.of(first, second)) {
            verify(child).close();
            when(child.awaitTermination(any(ToolDocs.nonNullClass(Duration.class))))
                    .thenReturn(true);
        }
        spawner.stopRuntime(group.groupId());
        for (var child : List.of(first, second)) {
            verify(child).close();
            verify(child, atLeast(2)).awaitTermination(any(ToolDocs.nonNullClass(Duration.class)));
        }
        spawner.stopRuntime(group.groupId());
        for (var child : List.of(first, second)) {
            verify(child).close();
            verify(child, atLeast(2)).awaitTermination(any(ToolDocs.nonNullClass(Duration.class)));
        }
        GroupTestHost.required(registry.get(group.groupId()));
    }

    @Test
    void runtimeCloseAttemptsEveryGroupAndRetainsFailuresForRetry() throws Exception {
        var fixture = new GroupTestHost();
        var child = GroupTestHost.child("member");
        when(child.awaitTermination(any(ToolDocs.nonNullClass(Duration.class)))).thenReturn(false);
        when(fixture.agents.open(anyString(), anyString(), any())).thenReturn(child);
        fixture.create();
        fixture.runtime.operations().createMate("one", "work");
        var original = fixture.configuration;
        var secondContext =
                new AgentConfiguration.Context(
                        original.owner(),
                        original.scope(),
                        original.agents(),
                        "second-leader",
                        original.base(),
                        original.authorizedTools(),
                        "task two");
        fixture.caller =
                new PluginHost.Invocation(
                        "owner",
                        fixture.scope.sessionId(),
                        "second-leader",
                        "request-two",
                        "test-call");
        fixture.runtime.configure(secondContext);
        fixture.runtime.delegation().createGroup("second brief");
        fixture.runtime.operations().createMate("two", "work");
        var failure = assertThrows(IllegalStateException.class, fixture.runtime::close);
        assertEquals(1, failure.getSuppressed().length);
        verify(child, times(2)).close();
        assertEquals(2, fixture.runtime.registry().snapshot().size());
        when(child.awaitTermination(any(ToolDocs.nonNullClass(Duration.class)))).thenReturn(true);
        fixture.close();
        verify(child, times(2)).close();
        verify(child, times(4)).awaitTermination(any(ToolDocs.nonNullClass(Duration.class)));
        assertTrue(fixture.runtime.registry().snapshot().isEmpty());
    }

    @Test
    void failedCloseBlocksLaterDispatchWithoutPretendingDisbandSucceeded() {
        var board = new Blackboard();
        var registry = new GroupRegistry();
        var group =
                Group.create(
                                "leader",
                                "owner",
                                "work",
                                board,
                                ExecutionDag.linear(UUID.randomUUID(), List.of("work")))
                        .withMate("mate", "general");
        registry.put(group);
        var orchestrator = new GroupOrchestrator(registry, board);
        assertThrows(
                IllegalStateException.class,
                () ->
                        orchestrator.closeGroup(
                                group.groupId(),
                                () -> {
                                    throw new IllegalStateException("member still running");
                                }));
        orchestrator.tick(group.groupId());
        assertTrue(board.readAll(group.groupId()).isEmpty());
        assertNotEquals(
                GroupState.DISBANDED,
                GroupTestHost.required(registry.get(group.groupId())).state());
        orchestrator.closeGroup(
                group.groupId(), () -> registry.disband(group.groupId(), Instant.now()));
        assertEquals(
                GroupState.DISBANDED,
                GroupTestHost.required(registry.get(group.groupId())).state());
    }
}
