package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentResult;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.group.GroupTools.CreateGroup;
import top.focess.veto.group.GroupTools.DisbandGroup;
import top.focess.veto.group.GroupTools.DispatchTask;
import top.focess.veto.group.GroupTools.PostMessage;

/**
 * Verifies the {@code GroupTools} native-tool bodies are wired to the runtime (GroupSpawner +
 * Blackboard). {@code create_group} now spawns a one-shot Leader agent to author the DAG, registers
 * the group, spawns one Mate per skillset, and returns {@code "delegated"} (no group id) - the
 * Leader agent is stubbed here so the authoring path is exercised without an LLM.
 */
class GroupToolsWiringTest {

    private final Blackboard blackboard = new Blackboard();
    private final GroupRegistry registry = new GroupRegistry();
    private final GroupOrchestrator orchestrator =
            new GroupOrchestrator(registry, blackboard, new HeuristicLeader());
    private final GroupSpawner spawner =
            new GroupSpawner(
                    blackboard, registry, orchestrator, new MateBreakerRegistry(), 50, null);

    /** A stub Agent whose {@code await} returns a fixed result (the Leader's authored DAG JSON). */
    private static final class StubAgent implements Agent {
        private final AgentPersona persona;
        private final AgentResult result;

        StubAgent(AgentPersona persona, AgentResult result) {
            this.persona = persona;
            this.result = result;
        }

        @Override
        public @NonNull String id() {
            return persona.id();
        }

        @Override
        public @NonNull String name() {
            return persona.name();
        }

        @Override
        public @NonNull AgentPersona persona() {
            return persona;
        }

        @Override
        public @NonNull Set<String> whitelistedTools() {
            return Set.of();
        }

        @Override
        public @NonNull AgentState state() {
            return AgentState.IDLE;
        }

        @Override
        public void submit(@NonNull String prompt) {}

        @Override
        public void submit(@NonNull String prompt, @Nullable Consumer<AgentResult> callback) {}

        @Override
        public @NonNull AgentResult await(@NonNull Duration timeout)
                throws TimeoutException, InterruptedException {
            return result;
        }

        @Override
        public @NonNull CompletableFuture<AgentResult> result() {
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void terminate() {}

        @Override
        public @NonNull List<TurnRecord> history() {
            return List.of();
        }

        @Override
        public @NonNull ReadHistory readHistory() {
            return new ReadHistory();
        }

        @Override
        public void compact() {}
    }

    /**
     * A stub AgentFactory: the Leader gets the authored-DAG result; Mates get a failure (unused).
     */
    private static final class StubAgentFactory implements GroupSpawner.AgentFactory {
        private final AgentResult leaderResult;

        StubAgentFactory(AgentResult leaderResult) {
            this.leaderResult = leaderResult;
        }

        @Override
        public Agent create(@NonNull AgentPersona persona) {
            AgentResult r =
                    persona.role() == top.focess.veto.agent.identity.Role.LEADER
                            ? leaderResult
                            : AgentResult.failure("stub mate", Map.of());
            return new StubAgent(persona, r);
        }
    }

    @Test
    void createGroupDelegatesAndRegistersGroupWithRecallBrief() {
        AgentResult leaderResult =
                AgentResult.success(
                        "{\"nodes\":[{\"nodeId\":\"n1\",\"description\":\"do the thing\",\"skillset\":\"coding\"}]}",
                        Map.of());
        CreateGroup create = new CreateGroup(spawner, new StubAgentFactory(leaderResult));

        ToolCallContextHolder.set("agent-1", UUID.randomUUID());
        try {
            String result = create.execute(new CreateGroup.Args("do the thing", null));
            assertEquals("delegated", result, "create_group returns 'delegated', not a group id");

            // A RECALL brief is requested to seed the caller's context with the authored plan.
            List<TurnRecord> pending = ToolCallContextHolder.drainPendingTurns();
            assertEquals(1, pending.size(), "a RECALL is requested");
            assertEquals(TurnType.RECALL, pending.get(0).type());

            // The group is registered (found via the registry snapshot - no id was returned).
            assertEquals(1, registry.snapshot().size(), "one group registered");
            Group g = registry.snapshot().values().iterator().next();
            assertTrue(g.isActive());
            assertEquals("do the thing", g.contextBrief());
            assertEquals(
                    1, g.dag().nodes().size(), "the Leader-authored single-node DAG is registered");
            assertEquals("do the thing", g.dag().nodes().get(0).description());
            spawner.disband(g.groupId());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void createGroupRegistersLeaderAuthoredDag() {
        String dagJson =
                "{\"nodes\":["
                        + "{\"nodeId\":\"n1\",\"description\":\"a\",\"skillset\":\"coding\"},"
                        + "{\"nodeId\":\"n2\",\"description\":\"b\",\"skillset\":\"testing\","
                        + "\"dependsOn\":[\"n1\"]}"
                        + "]}";
        AgentResult leaderResult = AgentResult.success(dagJson, Map.of());
        CreateGroup create = new CreateGroup(spawner, new StubAgentFactory(leaderResult));

        ToolCallContextHolder.set("agent-2", UUID.randomUUID());
        try {
            String result = create.execute(new CreateGroup.Args("brief", null));
            assertEquals("delegated", result);
            ToolCallContextHolder.drainPendingTurns(); // discard the RECALL

            Group g = registry.snapshot().values().iterator().next();
            assertEquals(2, g.dag().nodes().size(), "the Leader-authored 2-node DAG is registered");
            assertEquals("n2", g.dag().nodes().get(1).nodeId());
            spawner.disband(g.groupId());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void createGroupFallsBackToLinearDagWhenLeaderFails() {
        // Leader returns a failure -> authorDag falls back to a single-node linear DAG.
        AgentResult leaderResult = AgentResult.failure("no model configured", Map.of());
        CreateGroup create = new CreateGroup(spawner, new StubAgentFactory(leaderResult));

        ToolCallContextHolder.set("agent-3", UUID.randomUUID());
        try {
            String result = create.execute(new CreateGroup.Args("fallback brief", null));
            assertEquals("delegated", result);
            ToolCallContextHolder.drainPendingTurns();

            Group g = registry.snapshot().values().iterator().next();
            assertEquals(1, g.dag().nodes().size(), "fallback is a single-node linear DAG");
            spawner.disband(g.groupId());
        } finally {
            ToolCallContextHolder.clear();
        }
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

    @Test
    void disbandGroupTearsDownRegisteredGroup() {
        AgentResult leaderResult =
                AgentResult.success(
                        "{\"nodes\":[{\"nodeId\":\"n1\",\"description\":\"x\",\"skillset\":\"coding\"}]}",
                        Map.of());
        CreateGroup create = new CreateGroup(spawner, new StubAgentFactory(leaderResult));
        ToolCallContextHolder.set("agent-4", UUID.randomUUID());
        try {
            create.execute(new CreateGroup.Args("do the thing", null));
            ToolCallContextHolder.drainPendingTurns();
            Group g = registry.snapshot().values().iterator().next();

            DisbandGroup disband = new DisbandGroup(spawner);
            disband.execute(new DisbandGroup.Args(g.groupId().toString()));
            assertEquals(
                    Group.GroupState.DISBANDED,
                    registry.get(g.groupId()).state(),
                    "disband flips the group to DISBANDED");
        } finally {
            ToolCallContextHolder.clear();
        }
    }
}
