package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.group.GroupOrchestrator.NodeEdit;

/** Tests for the Leader's node-authoring tools (group_management_lld.md §2-§3). */
@SuppressWarnings("initialization.field.uninitialized")
class DagToolsTest {

    private @NonNull Blackboard blackboard;
    private @NonNull GroupRegistry registry;
    private @NonNull GroupOrchestrator orchestrator;
    private DagTools.@NonNull CreateNode createNode;
    private DagTools.@NonNull RemoveNode removeNode;

    @BeforeEach
    void setUp() {
        blackboard = new Blackboard();
        registry = new GroupRegistry();
        orchestrator = new GroupOrchestrator(registry, blackboard);
        createNode = new DagTools.CreateNode(orchestrator);
        removeNode = new DagTools.RemoveNode(orchestrator);
    }

    @AfterEach
    void tearDown() {
        ToolCallContextHolder.clear();
    }

    /** Registers an active group with an empty plan and returns its id. */
    private @NonNull UUID activeGroup() {
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        new ExecutionDag(UUID.randomUUID(), List.of()));
        registry.put(g);
        return g.groupId();
    }

    private static @NonNull DagNode findNode(@NonNull Group g, @NonNull String nodeId) {
        return g.dag().nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow();
    }

    // --- engine ops ---

    @Test
    void addNodeAppendsPendingNode() {
        UUID groupId = activeGroup();
        NodeEdit edit =
                orchestrator.addNode(groupId, "node-1", "Implement login", "coding", Set.of());
        assertInstanceOf(
                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(NodeEdit.Applied.class), edit);
        DagNode node = findNode(requireGroup(registry.get(groupId)), "node-1");
        assertEquals(DagNode.NodeState.PENDING, node.state());
        assertEquals("coding", node.requiredSkillset());
    }

    @Test
    void addNodeRejectsDuplicateId() {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        NodeEdit edit = orchestrator.addNode(groupId, "node-1", "b", "testing", Set.of());
        NodeEdit.Rejected r =
                assertInstanceOf(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                        edit);
        assertTrue(r.reason().contains("already exists"), r.reason());
    }

    @Test
    void addNodeRejectsUnknownDependency() {
        UUID groupId = activeGroup();
        NodeEdit edit = orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of("node-9"));
        NodeEdit.Rejected r =
                assertInstanceOf(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                        edit);
        assertTrue(r.reason().contains("unknown dependency node-9"), r.reason());
    }

    @Test
    void addNodeRejectsStaleDependency() {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        orchestrator.removeNode(groupId, "node-1");
        NodeEdit edit = orchestrator.addNode(groupId, "node-2", "b", "coding", Set.of("node-1"));
        NodeEdit.Rejected r =
                assertInstanceOf(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                        edit);
        assertTrue(r.reason().contains("stale"), r.reason());
    }

    @Test
    void addNodeRejectsUnknownGroup() {
        NodeEdit edit = orchestrator.addNode(UUID.randomUUID(), "node-1", "a", "coding", Set.of());
        assertInstanceOf(
                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(NodeEdit.Rejected.class), edit);
    }

    @Test
    void removeNodeMarksStale() {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        NodeEdit edit = orchestrator.removeNode(groupId, "node-1");
        assertInstanceOf(
                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(NodeEdit.Applied.class), edit);
        assertEquals(
                DagNode.NodeState.STALE,
                findNode(requireGroup(registry.get(groupId)), "node-1").state());
    }

    @Test
    void removeNodeRefusesLiveDependents() {
        UUID groupId = activeGroup();
        orchestrator.addNode(groupId, "node-1", "a", "coding", Set.of());
        orchestrator.addNode(groupId, "node-2", "b", "testing", Set.of("node-1"));
        NodeEdit edit = orchestrator.removeNode(groupId, "node-1");
        NodeEdit.Rejected r =
                assertInstanceOf(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                        edit);
        assertTrue(r.reason().contains("node-2"), r.reason());
        // The node is untouched.
        assertEquals(
                DagNode.NodeState.PENDING,
                findNode(requireGroup(registry.get(groupId)), "node-1").state());
    }

    @Test
    void removeNodeRefusesVerifiedNode() {
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
                assertInstanceOf(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                        edit);
        assertTrue(r.reason().contains("verified"), r.reason());
    }

    // --- tool surface ---

    @Test
    void createNodeReturnsProseOnSuccess() {
        UUID groupId = activeGroup();
        ToolCallContextHolder.set("leader-1", UUID.randomUUID(), groupId);
        String out =
                createNode.execute(
                        new DagTools.CreateNode.Args("node-1", "Implement login", "coding", null));
        assertEquals(
                "Node created: node-1 (skillset: coding). It dispatches as soon as a coding mate is provisioned.",
                out);
    }

    @Test
    void createNodeMentionsDependenciesOnSuccess() {
        UUID groupId = activeGroup();
        ToolCallContextHolder.set("leader-1", UUID.randomUUID(), groupId);
        createNode.execute(new DagTools.CreateNode.Args("node-1", "a", "coding", null));
        String out =
                createNode.execute(
                        new DagTools.CreateNode.Args(
                                "node-2", "Test login", "testing", List.of("node-1")));
        assertEquals(
                "Node created: node-2 (skillset: testing, depends on: node-1). It dispatches when its dependencies verify.",
                out);
    }

    @Test
    void createNodeMapsRejectionToInstructiveString() {
        UUID groupId = activeGroup();
        ToolCallContextHolder.set("leader-1", UUID.randomUUID(), groupId);
        String out =
                createNode.execute(
                        new DagTools.CreateNode.Args("node-1", "a", "coding", List.of("node-9")));
        assertEquals(
                "Node not created: unknown dependency node-9. Create dependencies before the nodes that need them.",
                out);
    }

    @Test
    void createNodeRequiresGroupContext() {
        ToolCallContextHolder.set("agent-1", UUID.randomUUID()); // STANDALONE: no groupId
        String out =
                createNode.execute(new DagTools.CreateNode.Args("node-1", "a", "coding", null));
        assertTrue(out.startsWith("Node not created: no active group"), out);
    }

    @Test
    void removeNodeReturnsProseOnSuccess() {
        UUID groupId = activeGroup();
        ToolCallContextHolder.set("leader-1", UUID.randomUUID(), groupId);
        createNode.execute(new DagTools.CreateNode.Args("node-2", "a", "coding", null));
        String out = removeNode.execute(new DagTools.RemoveNode.Args("node-2"));
        assertEquals("Node removed: node-2 (marked stale).", out);
    }

    @Test
    void removeNodeMapsDependentRefusalToInstructiveString() {
        UUID groupId = activeGroup();
        ToolCallContextHolder.set("leader-1", UUID.randomUUID(), groupId);
        createNode.execute(new DagTools.CreateNode.Args("node-1", "a", "coding", null));
        createNode.execute(
                new DagTools.CreateNode.Args("node-3", "b", "testing", List.of("node-1")));
        String out = removeNode.execute(new DagTools.RemoveNode.Args("node-1"));
        assertEquals(
                "Node not removed: node-3 depends on node-1. Remove or re-plan it first.", out);
    }

    @Test
    void toolNamesAreSnakeCase() {
        assertEquals("create_node", createNode.getName());
        assertEquals("remove_node", removeNode.getName());
    }

    private static @NonNull Group requireGroup(Group group) {
        if (group == null) throw new AssertionError("expected group");
        return group;
    }
}
