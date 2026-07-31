package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.mcp.ToolResult;
import top.focess.veto.group.GroupTools.CreateGroup;
import top.focess.veto.group.GroupTools.DisbandGroup;
import top.focess.veto.group.GroupTools.PostMessage;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.model.tier.DefaultModelTierRegistry;
import top.focess.veto.model.tier.ModelTierProperties;

/**
 * Verifies the Model B {@code GroupTools} native-tool bodies are wired to the runtime (GroupSpawner
 * + Blackboard + GroupRegistry). {@code create_group} registers an empty group and requests a
 * forward transform (STANDALONE -> Leader); {@code disband_group} tears the group down and requests
 * the reverse transform; {@code post_message} posts to the Blackboard. All three resolve the
 * caller's group from the {@link ToolCallContext}, so none takes a {@code groupId} argument.
 */
class GroupToolsWiringTest {

    private final Blackboard blackboard = new Blackboard();
    private final GroupRegistry registry = new GroupRegistry();
    private final GroupOrchestrator orchestrator = new GroupOrchestrator(registry, blackboard);
    private final DefaultModelTierRegistry tierRegistry =
            new DefaultModelTierRegistry(new ModelTierProperties());
    private final GroupSpawner spawner =
            new GroupSpawner(blackboard, registry, orchestrator, new MateBreakerRegistry(), 50);
    private final LeaderBinding leaderBinding = new LeaderBinding("TOP", "base", tierRegistry);
    private final RoleToolFilter roleToolFilter = new RoleToolFilter(new StubToolEngine());

    /**
     * A stub ToolEngine whose active-tools set is empty - the filter resolves an empty Leader set.
     */
    private static final class StubToolEngine implements ToolEngine {
        @Override
        public @NonNull List<ToolDefinition> getActiveTools(@Nullable Set<String> whitelist) {
            return List.of();
        }

        @Override
        public @Nullable ToolDefinition resolveDefinition(@NonNull String toolName) {
            return null;
        }

        @Override
        public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
            return new ToolResult(call.toolName(), call.callId(), true, "");
        }
    }

    @Test
    void createGroupRegistersEmptyGroupAndRequestsTransform() {
        CreateGroup create = new CreateGroup(spawner, leaderBinding, roleToolFilter);

        ToolCallContextHolder.set("agent-1", UUID.randomUUID());
        try {
            String result = create.execute(new CreateGroup.Args("do the thing"));
            assertEquals("", result, "create_group returns an empty result on success");

            // A forward transform (STANDALONE -> Leader) is requested - not a recall.
            ToolCallContextHolder.TransformRequest request = ToolCallContextHolder.drainTransform();
            assertInstanceOf(ToolCallContextHolder.TransformRequest.ToLeader.class, request);
            ToolCallContextHolder.TransformDirective directive =
                    ((ToolCallContextHolder.TransformRequest.ToLeader) request).directive();
            assertEquals("do the thing", directive.brief());
            assertEquals("deepseek-chat", directive.leaderBinding().model());

            // An empty group is registered (no DAG nodes, no Mates).
            assertEquals(1, registry.snapshot().size(), "one group registered");
            Group g = registry.snapshot().values().iterator().next();
            assertTrue(g.isActive());
            assertEquals(
                    directive.groupId(), g.groupId(), "the directive stamps the registered group");
            assertTrue(g.dag().nodes().isEmpty(), "the group starts with an empty DAG");
            spawner.disband(g.groupId());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void createGroupRefusesBlankBrief() {
        CreateGroup create = new CreateGroup(spawner, leaderBinding, roleToolFilter);
        ToolCallContextHolder.set("agent-blank", UUID.randomUUID());
        try {
            String result = create.execute(new CreateGroup.Args("   "));
            assertTrue(result.startsWith("Group not created:"), "blank brief is refused");
            assertNull(ToolCallContextHolder.drainTransform(), "no transform requested on refusal");
            assertTrue(registry.snapshot().isEmpty(), "no group registered on refusal");
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void disbandGroupTearsDownGroupAndRequestsReverseTransform() {
        Group g = spawner.registerEmptyGroup("leader", "default", "brief");

        DisbandGroup disband = new DisbandGroup(spawner, registry);
        ToolCallContextHolder.set("leader", UUID.randomUUID(), g.groupId());
        try {
            String result = disband.execute(new DisbandGroup.Args());
            assertEquals("", result, "disband_group returns an empty result on success");

            // A reverse transform (Leader -> STANDALONE) is requested.
            ToolCallContextHolder.TransformRequest request = ToolCallContextHolder.drainTransform();
            assertInstanceOf(ToolCallContextHolder.TransformRequest.ToStandalone.class, request);
            String brief = ((ToolCallContextHolder.TransformRequest.ToStandalone) request).brief();
            assertTrue(
                    brief.contains("Delegation complete"),
                    "the reverse-transform brief carries the outcome");

            assertEquals(
                    Group.GroupState.DISBANDED,
                    registry.get(g.groupId()).state(),
                    "disband flips the group to DISBANDED");
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void disbandGroupRefusesWithoutActiveGroup() {
        DisbandGroup disband = new DisbandGroup(spawner, registry);
        ToolCallContextHolder.set("leader", UUID.randomUUID()); // no groupId in context
        try {
            String result = disband.execute(new DisbandGroup.Args());
            assertTrue(result.startsWith("Group not disbanded:"), "no active group is refused");
            assertNull(
                    ToolCallContextHolder.drainTransform(),
                    "no reverse transform requested on refusal");
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void postMessagePostsToBlackboardForReceiver() {
        Group g = spawner.registerEmptyGroup("leader", "default", "brief");

        PostMessage post = new PostMessage(blackboard);
        ToolCallContextHolder.set("leader", UUID.randomUUID(), g.groupId());
        try {
            String result = post.execute(new PostMessage.Args("FEEDBACK", "mate-1", "oops"));
            assertEquals("posted", result);

            List<BlackboardMessage> forMate = blackboard.readFor(g.groupId(), "mate-1");
            assertEquals(1, forMate.size());
            assertEquals(BlackboardMessage.MessageType.FEEDBACK, forMate.get(0).type());
            assertEquals("oops", forMate.get(0).payload());
            assertEquals(
                    "LEADER",
                    forMate.get(0).senderId(),
                    "sender is the Leader (blackboard identity)");
            spawner.disband(g.groupId());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void postMessageRefusesWithoutActiveGroup() {
        PostMessage post = new PostMessage(blackboard);
        ToolCallContextHolder.set("leader", UUID.randomUUID()); // no groupId in context
        try {
            String result = post.execute(new PostMessage.Args("STATUS", "LEADER", "note"));
            assertTrue(result.startsWith("Not posted:"), "no active group is refused");
        } finally {
            ToolCallContextHolder.clear();
        }
    }
}
