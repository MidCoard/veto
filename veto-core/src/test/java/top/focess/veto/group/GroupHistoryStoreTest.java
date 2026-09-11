package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class GroupHistoryStoreTest {
    @Test
    void restartViewKeepsIdleMembersAndRequestIdentityWithoutClaimingLiveExecution() {
        @NonNull GroupHistoryRepository repository = mock();
        List<GroupHistoryEntity> rows = new ArrayList<>();
        when(repository.save(any()))
                .thenAnswer(
                        invocation -> {
                            GroupHistoryEntity row = invocation.getArgument(0);
                            if (row == null) throw new AssertionError("Missing row");
                            rows.add(row);
                            return row;
                        });
        UUID session = UUID.randomUUID();
        when(repository.findBySessionIdOrderByRecordedAtAsc(session.toString())).thenReturn(rows);
        var store = new GroupHistoryStore(repository, new ObjectMapper().findAndRegisterModules());
        var registry = new GroupRegistry();
        registry.attachHistory(store);
        var group =
                Group.create(
                                "leader",
                                "user",
                                "work",
                                new Blackboard(),
                                new ExecutionDag(UUID.randomUUID(), List.of()),
                                "owner",
                                null,
                                ToolResultPresentationMode.BASIC,
                                false,
                                session)
                        .withMate("working", "analysis")
                        .withMate("idle", "review");
        List<DagNode> tasks = new ArrayList<>();
        for (DagNode.NodeState state :
                List.of(
                        DagNode.NodeState.PENDING,
                        DagNode.NodeState.RUNNING,
                        DagNode.NodeState.CANCEL_REQUESTED,
                        DagNode.NodeState.VERIFIED)) {
            tasks.add(
                    new DagNode(
                            state.name(),
                            "work",
                            "working",
                            "analysis",
                            Set.of(),
                            state,
                            new DagNode.ResultSuccess("saved report"),
                            2,
                            "dispatch",
                            "request"));
        }
        group = group.withDag(new ExecutionDag(group.groupId(), tasks));
        registry.put(group);
        var live = store.load(session.toString(), registry).getFirst();
        assertTrue(live.live());
        assertEquals("ACTIVE", live.state());
        var afterRestart = store.load(session.toString(), new GroupRegistry()).getFirst();
        assertEquals(group.mates(), afterRestart.mates());
        assertFalse(afterRestart.live());
        assertEquals("INTERRUPTED", afterRestart.state());
        for (var task : afterRestart.nodes().subList(0, 3)) {
            assertEquals("INTERRUPTED", task.state());
            assertEquals("request", task.requestId());
            assertEquals("dispatch", task.dispatchId());
            assertEquals(2, task.retries());
            assertTrue(task.report().contains("not replayed"));
        }
        assertEquals("COMPLETED", afterRestart.nodes().get(3).state());
        assertEquals("saved report", afterRestart.nodes().get(3).report());
        assertEquals("RUNNING", afterRestart.changes().getFirst().nodes().get(1).state());
        assertEquals(
                1, rows.size(), "A history read must not persist a synthetic runtime transition");
    }

    @Test
    void oldSnapshotDoesNotInventAnEmptyOrCompleteRoster() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var old =
                new GroupHistoryView(
                        "group",
                        "leader",
                        "brief",
                        "ACTIVE",
                        Instant.now(),
                        List.of(),
                        false,
                        false,
                        List.of());
        var json = mapper.valueToTree(old);
        if (!(json instanceof ObjectNode object)) throw new AssertionError("Missing object");
        object.remove("mates");
        var restored = mapper.treeToValue(object, ToolDocs.nonNullClass(GroupHistoryView.class));
        assertNull(restored.mates());
        assertEquals("INTERRUPTED", restored.withoutRuntime().state());
    }

    @Test
    void savedTransitionsCanBeReadWithoutAnyLiveGroup() {
        @NonNull GroupHistoryRepository repository = mock();
        List<GroupHistoryEntity> rows = new ArrayList<>();
        when(repository.save(any()))
                .thenAnswer(
                        invocation -> {
                            GroupHistoryEntity row = invocation.getArgument(0);
                            if (row == null) throw new AssertionError("Missing row");
                            rows.add(row);
                            return row;
                        });
        UUID session = UUID.randomUUID();
        when(repository.findBySessionIdOrderByRecordedAtAsc(session.toString())).thenReturn(rows);
        var store = new GroupHistoryStore(repository, new ObjectMapper().findAndRegisterModules());
        var registry = new GroupRegistry();
        registry.attachHistory(store);
        var group =
                Group.create(
                        "leader",
                        "user",
                        "read",
                        new Blackboard(),
                        ExecutionDag.linear(UUID.randomUUID(), List.of("page")),
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        session);
        registry.put(group);
        registry.put(group);
        registry.disband(group.groupId(), Instant.now());
        assertEquals(2, rows.size(), "Unchanged polling must not create duplicate snapshots");
        var restored = store.load(session.toString(), new GroupRegistry()).get(0);
        assertFalse(restored.live());
        assertEquals("DISBANDED", restored.state());
        assertEquals("page", restored.nodes().get(0).id());
        assertEquals(2, restored.changes().size());
    }
}
