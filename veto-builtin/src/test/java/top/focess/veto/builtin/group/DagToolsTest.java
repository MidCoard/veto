package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.plugin.PluginHost;

/** Tests for the Leader's execution-DAG node-authoring tools. */
class DagToolsTest {

    private final @NonNull GroupTestHost fixture = new GroupTestHost();
    private final @NonNull Group active = fixture.create();
    private final @NonNull Blackboard blackboard = active.blackboard();
    private final @NonNull GroupRegistry registry = fixture.runtime.registry();
    private final @NonNull GroupOrchestrator orchestrator =
            new GroupOrchestrator(registry, blackboard);
    private final DagTools.@NonNull CreateNode createNode =
            new DagTools.CreateNode(fixture.runtime.operations());
    private final DagTools.@NonNull RemoveNode removeNode =
            new DagTools.RemoveNode(fixture.runtime.operations());

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private void setContext(@NonNull String agentId, UUID groupId) {
        fixture.caller =
                new PluginHost.Invocation(
                        "owner",
                        fixture.scope.sessionId(),
                        agentId.equals("leader-1") ? "leader" : agentId,
                        null,
                        "test-call");
    }

    private @NonNull UUID activeGroup() {
        return active.groupId();
    }

    private static @NonNull DagNode findNode(@NonNull Group g, @NonNull String nodeId) {
        return g.dag().nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void explicitTaskUsesExistingMemberWithoutSkillsetParameter() throws Exception {
        UUID id = activeGroup();
        Group group = registry.get(id);
        if (group == null) throw new AssertionError("Missing group");
        registry.put(group.withMate("alice", "Review calculations"));
        setContext("leader-1", id);
        var tool = new CollaborationTools.CreateTask(createNode.groupControlCapability());
        execute(tool, new CollaborationTools.CreateTask.Args("first", "Calculate", "alice", null));
        execute(tool, new CollaborationTools.CreateTask.Args("second", "Recheck", "alice", null));
        Group updated = orchestrator.tick(id);
        if (updated == null) throw new AssertionError("Missing group");
        assertEquals(1, updated.mates().size());
        assertEquals("alice", findNode(updated, "first").assignedMateId());
        assertEquals("alice", findNode(updated, "second").assignedMateId());
        assertEquals(DagNode.NodeState.RUNNING, findNode(updated, "first").state());
        assertEquals(DagNode.NodeState.PENDING, findNode(updated, "second").state());
    }

    @Test
    void explicitTaskRejectsUnknownMemberWithoutCreatingWork() throws Exception {
        UUID id = activeGroup();
        setContext("leader-1", id);
        var tool = new CollaborationTools.CreateTask(createNode.groupControlCapability());
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        execute(
                                tool,
                                new CollaborationTools.CreateTask.Args(
                                        "first", "Calculate", "outsider", null)));
        Group group = registry.get(id);
        if (group == null) throw new AssertionError("Missing group");
        assertTrue(group.dag().nodes().isEmpty());
        assertTrue(group.mates().isEmpty());
    }

    // --- engine ops ---

    @Test
    void addNodeAppendsPendingNode() throws Exception {
        UUID groupId = activeGroup();
        NodeEdit edit =
                orchestrator.addNode(groupId, "node-1", "Implement login", "coding", Set.of());
        assertInstanceOf(ToolDocs.nonNullClass(NodeEdit.Applied.class), edit);
        DagNode node = findNode(requireGroup(registry.get(groupId)), "node-1");
        assertEquals(DagNode.NodeState.PENDING, node.state());
        assertEquals("coding", node.requiredSkillset());
    }

    @Test
    void addNodeRejectsDuplicateId() throws Exception {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        NodeEdit edit = orchestrator.addNode(groupId, "node-1", "b", "testing", Set.of());
        NodeEdit.Rejected r =
                assertInstanceOf(ToolDocs.nonNullClass(NodeEdit.Rejected.class), edit);
        assertTrue(r.reason().contains("already exists"), r.reason());
    }

    @Test
    void addNodeRejectsUnknownDependency() throws Exception {
        UUID groupId = activeGroup();
        NodeEdit edit = orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of("node-9"));
        NodeEdit.Rejected r =
                assertInstanceOf(ToolDocs.nonNullClass(NodeEdit.Rejected.class), edit);
        assertTrue(r.reason().contains("unknown dependency node-9"), r.reason());
    }

    @Test
    void addNodeRejectsStaleDependency() throws Exception {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        orchestrator.removeNode(groupId, "node-1");
        NodeEdit edit = orchestrator.addNode(groupId, "node-2", "b", "coding", Set.of("node-1"));
        NodeEdit.Rejected r =
                assertInstanceOf(ToolDocs.nonNullClass(NodeEdit.Rejected.class), edit);
        assertTrue(r.reason().contains("stale"), r.reason());
    }

    @Test
    void addNodeRejectsUnknownGroup() throws Exception {
        NodeEdit edit = orchestrator.addNode(UUID.randomUUID(), "node-1", "a", "coding", Set.of());
        assertInstanceOf(ToolDocs.nonNullClass(NodeEdit.Rejected.class), edit);
    }

    @Test
    void removeNodeMarksStale() throws Exception {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        NodeEdit edit = orchestrator.removeNode(groupId, "node-1");
        assertInstanceOf(ToolDocs.nonNullClass(NodeEdit.Applied.class), edit);
        assertEquals(
                DagNode.NodeState.STALE,
                findNode(requireGroup(registry.get(groupId)), "node-1").state());
    }

    @Test
    void removeNodeRefusesLiveDependents() throws Exception {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        orchestrator.addNode(groupId, "node-2", "b", "testing", Set.of("node-1"));
        NodeEdit edit = orchestrator.removeNode(groupId, "node-1");
        NodeEdit.Rejected r =
                assertInstanceOf(ToolDocs.nonNullClass(NodeEdit.Rejected.class), edit);
        assertTrue(r.reason().contains("node-2"), r.reason());
        // The node is untouched.
        assertEquals(
                DagNode.NodeState.PENDING,
                findNode(requireGroup(registry.get(groupId)), "node-1").state());
    }

    @Test
    void removeNodeRefusesVerifiedNode() throws Exception {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        Group g = requireGroup(registry.get(groupId));
        DagNode verified =
                new DagNode(
                        "node-1",
                        "a",
                        null,
                        "coding",
                        Set.of(),
                        DagNode.NodeState.VERIFIED,
                        new DagNode.ResultArtifact("/out/a"),
                        0);
        registry.put(g.withDag(g.dag().withNode("node-1", verified)));
        NodeEdit edit = orchestrator.removeNode(groupId, "node-1");
        NodeEdit.Rejected r =
                assertInstanceOf(ToolDocs.nonNullClass(NodeEdit.Rejected.class), edit);
        assertTrue(r.reason().contains("verified"), r.reason());
    }

    // --- tool surface ---

    @Test
    void createNodeReturnsProseOnSuccess() throws Exception {
        UUID groupId = activeGroup();
        setContext("leader-1", groupId);
        String out =
                execute(
                        createNode,
                        new DagTools.CreateNode.Args(
                                "node-1", "Implement login", "coding", null, null, null));
        assertEquals("Node created: node-1 (skillset: coding). It is eligible for dispatch.", out);
    }

    @Test
    void createNodeMentionsDependenciesOnSuccess() throws Exception {
        UUID groupId = activeGroup();
        setContext("leader-1", groupId);
        execute(
                createNode,
                new DagTools.CreateNode.Args("node-1", "a", "coding", null, null, null));
        String out =
                execute(
                        createNode,
                        new DagTools.CreateNode.Args(
                                "node-2", "Test login", "testing", List.of("node-1"), null, null));
        assertEquals(
                "Node created: node-2 (skillset: testing, depends on: node-1). It becomes eligible after its dependencies verify.",
                out);
    }

    @Test
    void createNodeMapsRejectionToInstructiveString() throws Exception {
        UUID groupId = activeGroup();
        setContext("leader-1", groupId);
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                execute(
                                        createNode,
                                        new DagTools.CreateNode.Args(
                                                "node-1",
                                                "a",
                                                "coding",
                                                List.of("node-9"),
                                                null,
                                                null)));
        assertEquals(
                "Node not created: unknown dependency node-9. Create dependencies before the nodes that need them.",
                ToolErrors.normalize(error.getMessage()));
    }

    @Test
    void createNodeRequiresGroupContext() throws Exception {
        setContext("agent-1", null);
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                execute(
                                        createNode,
                                        new DagTools.CreateNode.Args(
                                                "node-1", "a", "coding", null, null, null)));
        String message = ToolErrors.normalize(error.getMessage());
        assertTrue(message.startsWith("Node not created: no active group"), message);
    }

    @Test
    void removeNodeReturnsProseOnSuccess() throws Exception {
        UUID groupId = activeGroup();
        setContext("leader-1", groupId);
        execute(
                createNode,
                new DagTools.CreateNode.Args("node-2", "a", "coding", null, null, null));
        String out = execute(removeNode, new DagTools.RemoveNode.Args("node-2"));
        assertEquals("Node removed: node-2 (marked stale).", out);
    }

    @Test
    void removeNodeMapsDependentRefusalToInstructiveString() throws Exception {
        UUID groupId = activeGroup();
        setContext("leader-1", groupId);
        execute(
                createNode,
                new DagTools.CreateNode.Args("node-1", "a", "coding", null, null, null));
        execute(
                createNode,
                new DagTools.CreateNode.Args(
                        "node-3", "b", "testing", List.of("node-1"), null, null));
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> execute(removeNode, new DagTools.RemoveNode.Args("node-1")));
        assertEquals(
                "Node not removed: node-3 depends on node-1. Remove or re-plan it first.",
                ToolErrors.normalize(error.getMessage()));
    }

    @Test
    void toolNamesAreSnakeCase() throws Exception {
        assertEquals("create_node", createNode.getName());
        assertEquals("remove_node", removeNode.getName());
    }

    private static @NonNull Group requireGroup(Group group) {
        if (group == null) throw new AssertionError("expected group");
        return group;
    }

    private static <T> @NonNull String execute(@NonNull GroupControlTool<T> tool, @NonNull T args) {
        return tool.execute(args);
    }
}
