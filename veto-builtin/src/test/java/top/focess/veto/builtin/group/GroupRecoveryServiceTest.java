package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.api.agent.tool.ToolDocs;

/** The same recovery invariants now execute through the plugin activation contract. */
class GroupRecoveryServiceTest {
    private @NonNull GroupHistoryView snapshot(
            @NonNull String state, @NonNull Instant created, Map<String, String> mates) {
        return new GroupHistoryView(
                UUID.randomUUID().toString(),
                "leader",
                "team",
                state,
                created,
                List.of(),
                false,
                false,
                List.of(),
                mates);
    }

    private void save(@NonNull GroupTestHost fixture, @NonNull GroupHistoryView view) {
        fixture.runtime.history().scope(fixture.scope);
        fixture.runtime.history().append(fixture.scope.sessionId(), view, view.createdAt(), null);
    }

    @Test
    void latestDisbandedTeamDoesNotResurrectOlderTeam() {
        try (var fixture = new GroupTestHost()) {
            save(fixture, snapshot("ACTIVE", Instant.EPOCH, Map.of("mate", "review")));
            save(fixture, snapshot("DISBANDED", Instant.EPOCH.plusSeconds(1), Map.of()));
            fixture.runtime.configure(fixture.configuration);
            assertTrue(fixture.runtime.registry().snapshot().isEmpty());
            verify(fixture.agents, never()).open(anyString(), anyString(), any());
        }
    }

    @Test
    void unknownLegacyRosterFailsWithoutInventingMembers() {
        try (var fixture = new GroupTestHost()) {
            save(fixture, snapshot("ACTIVE", Instant.EPOCH, null));
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.runtime.configure(fixture.configuration));
            assertTrue(fixture.runtime.registry().snapshot().isEmpty());
            verify(fixture.agents, never()).open(anyString(), anyString(), any());
        }
    }

    @Test
    void memberRestoreFailureKeepsSameRecoveryIdentityForRetry() throws Exception {
        try (var fixture = new GroupTestHost()) {
            var saved = snapshot("ACTIVE", Instant.EPOCH, Map.of("mate", "review"));
            save(fixture, saved);
            UUID id = UUID.fromString(saved.id());
            when(fixture.agents.open(anyString(), anyString(), any()))
                    .thenThrow(new IllegalStateException("history temporarily unavailable"))
                    .thenReturn(GroupTestHost.child("mate"));
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.runtime.configure(fixture.configuration));
            var groups = fixture.runtime.registry();
            assertEquals(GroupState.RECOVERING, GroupTestHost.required(groups.get(id)).state());
            var orchestrator = new GroupOrchestrator(groups, new Blackboard());
            assertInstanceOf(
                    ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                    orchestrator.addNode(
                            id, "premature", "work", "review", Set.of(), "mate", false));
            var intent = GroupTestHost.required(fixture.runtime.configure(fixture.configuration));
            assertNotNull(intent);
            assertEquals("LEADER", intent.profile().label());
            assertEquals(GroupState.ACTIVE, GroupTestHost.required(groups.get(id)).state());
            assertEquals(saved.mates(), GroupTestHost.required(groups.get(id)).mates());
            assertEquals(1, groups.snapshot().size());
            fixture.runtime.configure(fixture.configuration);
            verify(fixture.agents, times(2)).open(eq("mate"), eq("leader"), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "CANCEL_REQUESTED", "INTERRUPTED"})
    void unfinishedWorkKeepsCorrelationAndBecomesNonReplayable(@NonNull String state) {
        DagNode restored =
                GroupRuntime.restoreNode(
                        new GroupHistoryView.Node(
                                "task",
                                "review",
                                "mate",
                                "review",
                                List.of("dependency"),
                                state,
                                "partial report",
                                2,
                                "request",
                                "dispatch"));
        assertEquals(DagNode.NodeState.INTERRUPTED, restored.state());
        assertEquals("request", restored.requestId());
        assertEquals("dispatch", restored.dispatchId());
        assertEquals(2, restored.retryCount());
        assertEquals(Set.of("dependency"), restored.dependsOn());
        assertTrue(
                ((DagNode.ResultFailure) restored.result()).feedback().contains("partial report"));
        assertTrue(new ExecutionDag(UUID.randomUUID(), List.of(restored)).dispatchable().isEmpty());
    }
}
