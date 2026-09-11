package top.focess.veto.group;

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
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.util.Nullness;

class GroupRecoveryServiceTest {
    private final @NonNull GroupHistoryStore history = mock();
    private final @NonNull GroupSpawner spawner = mock();
    private final @NonNull SessionHistoryLoader turns = mock();
    private final @NonNull SessionAgentRegistry agents = mock();
    private final @NonNull LeaderBinding bindings = mock();
    private final @NonNull RoleToolFilter tools = mock();
    private final @NonNull VetoAgent leader = mock();
    private final @NonNull Workspace workspace = mock();
    private final GroupRegistry groups = new GroupRegistry();
    private final UUID session = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final GroupRecoveryService recovery =
            new GroupRecoveryService(
                    history, groups, new Blackboard(), spawner, turns, agents, bindings, tools);

    private void activate() {
        when(leader.id()).thenReturn("leader");
        when(bindings.binding("owner"))
                .thenReturn(
                        new AgentRunner.LlmBinding(
                                ProviderType.DEEPSEEK,
                                "model",
                                "key",
                                LlmOptions.defaults(),
                                "System"));
        when(agents.records(session)).thenReturn(List.of());
        when(tools.resolve(any())).thenReturn(Set.of());
        recovery.restore(
                leader, session, user, "owner", workspace, ToolResultPresentationMode.BASIC, false);
    }

    private @NonNull GroupHistoryView snapshot(
            @NonNull String state, @NonNull Instant created, Map<String, String> mates) {
        return new GroupHistoryView(
                UUID.randomUUID().toString(),
                "leader",
                "team",
                state,
                created,
                List.of(),
                true,
                false,
                List.of(),
                mates);
    }

    @Test
    void latestDisbandedTeamDoesNotResurrectOlderTeam() {
        when(history.latestSnapshots(session.toString()))
                .thenReturn(
                        List.of(
                                snapshot("ACTIVE", Instant.EPOCH, Map.of("mate", "review")),
                                snapshot("DISBANDED", Instant.EPOCH.plusSeconds(1), Map.of())));
        activate();
        assertTrue(groups.snapshot().isEmpty());
        verifyNoInteractions(spawner);
        verify(leader, never()).restoreLeader(any(), any(), any());
    }

    @Test
    void unknownLegacyRosterFailsWithoutInventingMembers() {
        when(history.latestSnapshots(session.toString()))
                .thenReturn(List.of(snapshot("ACTIVE", Instant.EPOCH, null)));
        assertThrows(IllegalStateException.class, this::activate);
        assertTrue(groups.snapshot().isEmpty());
        verifyNoInteractions(spawner);
    }

    @Test
    void memberRestoreFailureKeepsSameRecoveryIdentityForRetry() {
        GroupHistoryView saved = snapshot("ACTIVE", Instant.EPOCH, Map.of("mate", "review"));
        UUID id = UUID.fromString(saved.id());
        when(history.latestSnapshots(session.toString())).thenReturn(List.of(saved));
        doThrow(new IllegalStateException("history temporarily unavailable"))
                .doNothing()
                .when(spawner)
                .restoreMates(any(), any(), any());
        assertThrows(IllegalStateException.class, this::activate);
        assertEquals(Group.GroupState.RECOVERING, Nullness.requireNonNull(groups.get(id)).state());
        var orchestrator = new GroupOrchestrator(groups, new Blackboard());
        assertInstanceOf(
                Nullness.requireNonNull(GroupOrchestrator.NodeEdit.Rejected.class),
                orchestrator.addNode(id, "premature", "work", "review", Set.of(), "mate", false));
        assertThrows(
                IllegalStateException.class,
                () -> orchestrator.createMate(id, "extra", "review", spawner));
        verify(leader, never()).restoreLeader(any(), any(), any());
        activate();
        Group restored = Nullness.requireNonNull(groups.get(id));
        assertEquals(Group.GroupState.ACTIVE, restored.state());
        assertEquals(saved.mates(), restored.mates());
        assertEquals(1, groups.snapshot().size());
        activate();
        verify(spawner, times(2)).restoreMates(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "CANCEL_REQUESTED", "INTERRUPTED"})
    void unfinishedWorkKeepsCorrelationAndBecomesNonReplayable(String state) {
        DagNode restored =
                GroupRecoveryService.restoreNode(
                        new GroupHistoryView.Node(
                                "task",
                                "review",
                                "mate",
                                "review",
                                List.of("dependency"),
                                Nullness.requireNonNull(state),
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
        ExecutionDag dag = new ExecutionDag(UUID.randomUUID(), List.of(restored));
        assertTrue(dag.dispatchable().isEmpty());
    }
}
