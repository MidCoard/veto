package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.JsonValue;

class GroupHistoryStoreTest {
    @Test
    void restartViewKeepsIdleMembersAndRequestIdentityWithoutClaimingLiveExecution() {
        var fixture = new GroupTestHost();
        UUID session = UUID.fromString(fixture.scope.sessionId());
        var store = fixture.runtime.history();
        store.scope(fixture.scope);
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
                1,
                store.load(session.toString(), new GroupRegistry()).getFirst().changes().size(),
                "A history read must not persist a synthetic runtime transition");
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
        var fixture = new GroupTestHost();
        UUID session = UUID.fromString(fixture.scope.sessionId());
        var store = fixture.runtime.history();
        store.scope(fixture.scope);
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
                        session);
        registry.put(group);
        registry.put(group);
        registry.disband(group.groupId(), Instant.now());
        assertEquals(
                2,
                store.load(session.toString(), new GroupRegistry()).getFirst().changes().size(),
                "Unchanged polling must not create duplicate snapshots");
        var restored = store.load(session.toString(), new GroupRegistry()).get(0);
        assertFalse(restored.live());
        assertEquals("DISBANDED", restored.state());
        assertEquals("page", restored.nodes().get(0).id());
        assertEquals(2, restored.changes().size());
    }

    @Test
    void largeProfileRoundTripPreservesDescriptionGuidanceAndNestedRecoveryData() {
        try (var fixture = new GroupTestHost()) {
            var store = fixture.runtime.history();
            store.scope(fixture.scope);
            String description = "work🛰️".repeat(14000);
            String guidance = "Use evidence. ".repeat(2000);
            var task =
                    new JsonValue.ObjectValue(
                            Map.of(
                                    "id", new JsonValue.StringValue("task"),
                                    "requestId", new JsonValue.StringValue("request-one"),
                                    "dispatchId", new JsonValue.StringValue("attempt-one"),
                                    "state", new JsonValue.StringValue("INTERRUPTED")));
            var data =
                    new JsonValue.ObjectValue(
                            Map.of(
                                    "guidance", new JsonValue.StringValue(guidance),
                                    "tasks", new JsonValue.ArrayValue(List.of(task))));
            var profile =
                    new AgentProfile(
                            "Mate",
                            description,
                            "MATE",
                            Set.of("view_file"),
                            "LOW",
                            new AgentProfile.Prompt("builtin-mate-profile", data),
                            Map.of("origin", "legacy"));
            assertTrue(description.length() > 65536);
            store.profile(fixture.scope.sessionId(), "mate", profile);
            var restarted = new GroupHistoryStore(fixture.storage);
            assertEquals(profile, restarted.profile(fixture.scope.sessionId(), "mate"));
            var replacement =
                    new AgentProfile(
                            "Updated Mate",
                            "shorter description",
                            "MATE",
                            profile.tools(),
                            "MID",
                            profile.prompt(),
                            profile.metadata());
            restarted.profile(fixture.scope.sessionId(), "mate", replacement);
            assertEquals(replacement, store.profile(fixture.scope.sessionId(), "mate"));
        }
    }
}
