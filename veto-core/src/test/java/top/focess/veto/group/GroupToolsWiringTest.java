package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.group.GroupTools.CreateGroup;
import top.focess.veto.group.GroupTools.DisbandGroup;
import top.focess.veto.group.GroupTools.DispatchTask;
import top.focess.veto.group.GroupTools.PostMessage;

/**
 * Verifies the {@code GroupTools} native-tool bodies are wired to the runtime (GroupSpawner +
 * Blackboard), so the agent-facing tools actually do something when called — previously every
 * {@code execute(...)} returned {@code ""}.
 */
class GroupToolsWiringTest {

    private final Blackboard blackboard = new Blackboard();
    private final GroupRegistry registry = new GroupRegistry();
    private final GroupOrchestrator orchestrator =
            new GroupOrchestrator(registry, blackboard, new HeuristicLeader());
    private final GroupSpawner spawner =
            new GroupSpawner(
                    blackboard, registry, orchestrator, new MateBreakerRegistry(), 50, null);

    @Test
    void createGroupSpawnsRegisteredGroupThenDisbandTearsItDown() {
        CreateGroup create = new CreateGroup(spawner);
        String gid = create.execute(new CreateGroup.Args("do the thing", null));

        assertNotNull(gid, "create_group returns the group id");
        Group g = registry.get(UUID.fromString(gid));
        assertNotNull(g, "the group is registered");
        assertTrue(g.isActive());
        assertEquals("do the thing", g.contextBrief());
        assertEquals(1, g.dag().nodes().size(), "a single-node DAG is authored from the task");
        assertEquals("do the thing", g.dag().nodes().get(0).description());

        DisbandGroup disband = new DisbandGroup(spawner);
        String result = disband.execute(new DisbandGroup.Args(gid));
        assertEquals(Group.GroupState.DISBANDED, registry.get(g.groupId()).state());
        assertNotNull(result);
    }

    @Test
    void createGroupParsesAuthoredDagJson() {
        CreateGroup create = new CreateGroup(spawner);
        String dagJson =
                "{\"nodes\":["
                        + "{\"nodeId\":\"n1\",\"description\":\"a\",\"skillset\":\"coding\"},"
                        + "{\"nodeId\":\"n2\",\"description\":\"b\",\"skillset\":\"testing\","
                        + "\"dependsOn\":[\"n1\"]}"
                        + "]}";
        String gid = create.execute(new CreateGroup.Args("brief", dagJson));
        Group g = registry.get(UUID.fromString(gid));
        assertEquals(2, g.dag().nodes().size(), "the authored 2-node DAG is parsed");
        assertEquals("n2", g.dag().nodes().get(1).nodeId());
        spawner.disband(g.groupId());
    }

    @Test
    void dispatchTaskPostsTaskDispatchToMate() {
        Group g =
                spawner.spawnGroup(
                        "leader",
                        "default",
                        "brief",
                        ExecutionDag.linear(UUID.randomUUID(), List.of("n1")));

        DispatchTask dispatch = new DispatchTask(blackboard);
        dispatch.execute(new DispatchTask.Args(g.groupId().toString(), "mate-1", "do x"));

        List<BlackboardMessage> forMate = blackboard.readFor(g.groupId(), "mate-1");
        assertEquals(1, forMate.size());
        assertEquals(BlackboardMessage.MessageType.TASK_DISPATCH, forMate.get(0).type());
        assertEquals("mate-1:do x", forMate.get(0).payload());
        spawner.disband(g.groupId());
    }

    @Test
    void postMessagePostsMateToLeaderMessage() {
        Group g =
                spawner.spawnGroup(
                        "leader",
                        "default",
                        "brief",
                        ExecutionDag.linear(UUID.randomUUID(), List.of("n1")));

        PostMessage post = new PostMessage(blackboard);
        post.execute(new PostMessage.Args(g.groupId().toString(), "FEEDBACK", "oops"));

        List<BlackboardMessage> forLeader = blackboard.readFor(g.groupId(), "LEADER");
        assertEquals(1, forLeader.size());
        assertEquals(BlackboardMessage.MessageType.FEEDBACK, forLeader.get(0).type());
        assertEquals("oops", forLeader.get(0).payload());
        spawner.disband(g.groupId());
    }
}
