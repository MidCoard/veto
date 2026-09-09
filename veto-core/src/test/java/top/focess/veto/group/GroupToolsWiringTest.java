package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.capability.DelegationCapabilityImpl;
import top.focess.veto.agent.capability.GroupControlCapabilityImpl;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.group.GroupTools.CreateGroup;
import top.focess.veto.group.GroupTools.DisbandGroup;
import top.focess.veto.group.GroupTools.InspectGroup;
import top.focess.veto.group.GroupTools.PostMessage;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;

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
    private final ModelTierRegistry tierRegistry =
            new ModelTierRegistry() {
                @Override
                public @NonNull ModelBinding resolve(
                        @NonNull String username, @NonNull ModelTier tier) {
                    return new ModelBinding(
                            ProviderType.DEEPSEEK,
                            "deepseek-chat",
                            "deepseek-default",
                            0.7,
                            4096,
                            null);
                }

                @Override
                public @NonNull String activeProfile(@NonNull String username) {
                    return "default";
                }
            };
    private final GroupSpawner spawner =
            new GroupSpawner(blackboard, registry, orchestrator, new MateBreakerRegistry(), 50);
    private final LeaderBinding leaderBinding = new LeaderBinding("TOP", "base", tierRegistry);
    private final RoleToolFilter roleToolFilter = new RoleToolFilter(new StubToolEngine());

    /**
     * A stub ToolEngine whose active-tools set is empty - the filter resolves an empty Leader set.
     */
    private static final class StubToolEngine implements ToolEngine {
        @Override
        public @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist) {
            return List.of();
        }

        @Override
        public ToolDefinition resolveDefinition(@NonNull String toolName) {
            return null;
        }

        @Override
        public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
            return new ToolResult(call.toolName(), call.callId(), true, "");
        }
    }

    @Test
    void validCallerPermitCannotDisbandAnotherAgentsGroup() throws Exception {
        Group group = spawner.registerEmptyGroup("leader", "default", null, "brief");
        DisbandGroup tool =
                new DisbandGroup(
                        new GroupControlCapabilityImpl(
                                spawner, registry, blackboard, orchestrator));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "other-agent",
                        UUID.randomUUID(),
                        group.groupId(),
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
        try {
            assertThrows(
                    ToolDocs.nonNullClass(SecurityException.class),
                    () -> CapabilityTestCalls.execute(tool, new DisbandGroup.Args()));
            assertTrue(requireGroup(registry.get(group.groupId())).isActive());
            assertNull(ToolCallContextHolder.drainTransform());
        } finally {
            ToolCallContextHolder.clear();
            spawner.disband(group.groupId());
        }
    }

    @Test
    void createGroupRegistersEmptyGroupAndRequestsTransform() throws Exception {
        UUID parentSession = UUID.randomUUID();
        HitlRegistry hitlRegistry = new HitlRegistry();
        Workspace workspace = Workspace.single(Path.of("group-workspace"), PathMode.REAL);
        hitlRegistry.setWorkspace("agent-1", workspace);
        CreateGroup create =
                new CreateGroup(
                        new DelegationCapabilityImpl(
                                spawner, leaderBinding, roleToolFilter, hitlRegistry));

        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent-1",
                        UUID.randomUUID(),
                        null,
                        "owner",
                        parentSession,
                        ToolResultPresentationMode.BASIC,
                        true,
                        ToolExecutionPermit.empty()));
        try {
            String result =
                    CapabilityTestCalls.execute(create, new CreateGroup.Args("do the thing"));
            assertEquals("", result, "create_group returns an empty result on success");

            // A forward transform (STANDALONE -> Leader) is requested - not a recall.
            ToolCallContextHolder.TransformRequest request = ToolCallContextHolder.drainTransform();
            ToolCallContextHolder.TransformDirective directive =
                    assertInstanceOf(
                                    ToolDocs.nonNullClass(
                                            ToolCallContextHolder.TransformRequest.ToLeader.class),
                                    requireTransform(request))
                            .directive();
            assertEquals("do the thing", directive.brief());
            assertEquals("deepseek-chat", directive.leaderBinding().model());

            // An empty group is registered (no DAG nodes, no Mates).
            assertEquals(1, registry.snapshot().size(), "one group registered");
            Group g = registry.snapshot().values().iterator().next();
            assertTrue(g.isActive());
            assertEquals(parentSession, g.sessionId());
            assertEquals(
                    parentSession,
                    g.withMate("test-mate", "review")
                            .withDag(g.dag())
                            .withState(Group.GroupState.COMPLETED, g.createdAt())
                            .withoutMate("test-mate")
                            .sessionId());
            assertTrue(g.guidedEnabled(), "the group inherits guided availability");
            assertTrue(
                    g.withMate("test-mate", "review")
                            .withDag(g.dag())
                            .withState(Group.GroupState.COMPLETED, g.createdAt())
                            .withoutMate("test-mate")
                            .guidedEnabled(),
                    "group transitions retain guided availability");
            assertEquals(
                    directive.groupId(), g.groupId(), "the directive stamps the registered group");
            assertTrue(g.dag().nodes().isEmpty(), "the group starts with an empty DAG");
            assertSame(
                    workspace,
                    Nullness.requireNonNull(g.workspace()),
                    "the group carries the Leader's workspace");
            spawner.disband(g.groupId());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void createGroupRefusesBlankBrief() throws Exception {
        CreateGroup create =
                new CreateGroup(
                        new DelegationCapabilityImpl(
                                spawner, leaderBinding, roleToolFilter, new HitlRegistry()));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent-blank",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
        try {
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () -> CapabilityTestCalls.execute(create, new CreateGroup.Args("   ")));
            assertTrue(
                    ToolErrors.normalize(error.getMessage()).startsWith("Group not created:"),
                    "blank brief is refused");
            assertNull(ToolCallContextHolder.drainTransform(), "no transform requested on refusal");
            assertTrue(registry.snapshot().isEmpty(), "no group registered on refusal");
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void disbandGroupTearsDownGroupAndRequestsReverseTransform() throws Exception {
        Group g = spawner.registerEmptyGroup("leader", "default", null, "brief");

        DisbandGroup disband =
                new DisbandGroup(
                        new GroupControlCapabilityImpl(
                                spawner, registry, blackboard, orchestrator));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "leader",
                        UUID.randomUUID(),
                        g.groupId(),
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
        try {
            String result = CapabilityTestCalls.execute(disband, new DisbandGroup.Args());
            assertEquals("", result, "disband_group returns an empty result on success");

            // A reverse transform (Leader -> STANDALONE) is requested.
            ToolCallContextHolder.TransformRequest request = ToolCallContextHolder.drainTransform();
            String brief =
                    assertInstanceOf(
                                    ToolDocs.nonNullClass(
                                            ToolCallContextHolder.TransformRequest.ToStandalone
                                                    .class),
                                    requireTransform(request))
                            .brief();
            assertTrue(
                    brief.contains("Delegation complete"),
                    "the reverse-transform brief carries the outcome");

            assertEquals(
                    Group.GroupState.DISBANDED,
                    requireGroup(registry.get(g.groupId())).state(),
                    "disband flips the group to DISBANDED");
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void disbandGroupRefusesWithoutActiveGroup() throws Exception {
        DisbandGroup disband =
                new DisbandGroup(
                        new GroupControlCapabilityImpl(
                                spawner, registry, blackboard, orchestrator));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "leader",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
        try {
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () -> CapabilityTestCalls.execute(disband, new DisbandGroup.Args()));
            assertTrue(
                    ToolErrors.normalize(error.getMessage()).startsWith("Group not disbanded:"),
                    "no active group is refused");
            assertNull(
                    ToolCallContextHolder.drainTransform(),
                    "no reverse transform requested on refusal");
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void postMessageRecordsLeaderNote() throws Exception {
        Group g = spawner.registerEmptyGroup("leader", "default", null, "brief");
        registry.put(g.withMate("mate-1", "coding"));

        PostMessage post =
                new PostMessage(
                        new GroupControlCapabilityImpl(
                                spawner, registry, blackboard, orchestrator));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "leader",
                        UUID.randomUUID(),
                        g.groupId(),
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
        try {
            String result =
                    CapabilityTestCalls.execute(
                            post,
                            new PostMessage.Args(
                                    BlackboardMessage.MessageType.FEEDBACK, "LEADER", "oops"));
            assertEquals("posted", result);

            List<BlackboardMessage> forMate = blackboard.readFor(g.groupId(), "LEADER");
            assertEquals(1, forMate.size());
            assertEquals(BlackboardMessage.MessageType.FEEDBACK, forMate.get(0).type());
            assertEquals("oops", forMate.get(0).payload());
            assertEquals(
                    "LEADER",
                    forMate.get(0).senderId(),
                    "sender is the Leader (blackboard identity)");
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            CapabilityTestCalls.execute(
                                    post,
                                    new PostMessage.Args(
                                            BlackboardMessage.MessageType.TASK_DISPATCH,
                                            "mate-1",
                                            "hidden:work")));
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () ->
                            CapabilityTestCalls.execute(
                                    post,
                                    new PostMessage.Args(
                                            BlackboardMessage.MessageType.FEEDBACK,
                                            "mate-1",
                                            "silently ignored before")));
            assertTrue(blackboard.readFor(g.groupId(), "mate-1").isEmpty());
            spawner.disband(g.groupId());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void postMessageRefusesWithoutActiveGroup() throws Exception {
        PostMessage post =
                new PostMessage(
                        new GroupControlCapabilityImpl(
                                spawner, registry, blackboard, orchestrator));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "leader",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
        try {
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () ->
                                    CapabilityTestCalls.execute(
                                            post,
                                            new PostMessage.Args(
                                                    BlackboardMessage.MessageType.STATUS,
                                                    "LEADER",
                                                    "note")));
            assertTrue(
                    ToolErrors.normalize(error.getMessage()).startsWith("Not posted:"),
                    "no active group is refused");
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void postMessageRefusesDisbandedGroup() throws Exception {
        Group group = spawner.registerEmptyGroup("leader", "default", null, "brief");
        spawner.disband(group.groupId());
        PostMessage post =
                new PostMessage(
                        new GroupControlCapabilityImpl(
                                spawner, registry, blackboard, orchestrator));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "leader",
                        UUID.randomUUID(),
                        group.groupId(),
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
        try {
            ToolExecutionException error =
                    assertThrows(
                            ToolDocs.nonNullClass(ToolExecutionException.class),
                            () ->
                                    CapabilityTestCalls.execute(
                                            post,
                                            new PostMessage.Args(
                                                    BlackboardMessage.MessageType.STATUS,
                                                    "LEADER",
                                                    "note")));
            assertTrue(ToolErrors.normalize(error.getMessage()).contains("no longer active"));
            assertTrue(blackboard.readFor(group.groupId(), "LEADER").isEmpty());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void inspectGroupReturnsOnlyNewMateReportsAndCursor() throws Exception {
        Group g = spawner.registerEmptyGroup("leader", "default", null, "brief");
        registry.put(g.withMate("mate-1", "coding"));
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        g.groupId(),
                        "mate-1",
                        "LEADER",
                        BlackboardMessage.MessageType.ACCEPT,
                        "node-1:accept-base64:UmVwb3J0OiB0ZXN0cyBwYXNzZWQu",
                        0));

        InspectGroup inspect =
                new InspectGroup(
                        new GroupControlCapabilityImpl(
                                spawner, registry, blackboard, orchestrator));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "leader",
                        UUID.randomUUID(),
                        g.groupId(),
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
        try {
            String first = CapabilityTestCalls.execute(inspect, new InspectGroup.Args(0L, 0));
            assertTrue(first.contains("sender=mate-1 type=ACCEPT"));
            assertTrue(first.contains("nextSinceSeq: 1"));
            assertTrue(first.contains("Report: tests passed."));
            assertFalse(first.contains("accept-base64"));

            String second = CapabilityTestCalls.execute(inspect, new InspectGroup.Args(1L, 0));
            assertTrue(second.contains("New Mate messages:\n- (none)"));
        } finally {
            ToolCallContextHolder.clear();
            spawner.disband(g.groupId());
        }
    }

    private static ToolCallContextHolder.@NonNull TransformRequest requireTransform(
            ToolCallContextHolder.TransformRequest request) {
        if (request != null) {
            return request;
        }
        throw new AssertionError("expected a transform request");
    }

    private static @NonNull Group requireGroup(Group group) {
        if (group != null) {
            return group;
        }
        throw new AssertionError("expected group to remain registered");
    }
}
