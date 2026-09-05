package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.capability.GroupControlCapabilityImpl;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.group.GroupOrchestrator.NodeEdit;
import top.focess.veto.llm.core.ToolResultPresentationMode;

/** Tests for the Leader's execution-DAG node-authoring tools. */
@SuppressWarnings("initialization.field.uninitialized")
class DagToolsTest {

    private @NonNull Blackboard blackboard;
    private @NonNull GroupRegistry registry;
    private @NonNull GroupOrchestrator orchestrator;
    private DagTools.@NonNull CreateNode createNode;
    private DagTools.@NonNull RemoveNode removeNode;

    @BeforeEach
    void setUp() throws Exception {
        blackboard = new Blackboard();
        registry = new GroupRegistry();
        orchestrator = new GroupOrchestrator(registry, blackboard);
        createNode =
                new DagTools.CreateNode(
                        new GroupControlCapabilityImpl(
                                mock(ToolDocs.nonNullClass(GroupSpawner.class)),
                                registry,
                                blackboard,
                                orchestrator));
        removeNode =
                new DagTools.RemoveNode(
                        new GroupControlCapabilityImpl(
                                mock(ToolDocs.nonNullClass(GroupSpawner.class)),
                                registry,
                                blackboard,
                                orchestrator));
    }

    @AfterEach
    void tearDown() throws Exception {
        ToolCallContextHolder.clear();
    }

    private static void setContext(@NonNull String agentId, UUID groupId) throws Exception {
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agentId,
                        UUID.randomUUID(),
                        groupId,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
    }

    /** Registers an active group with an empty plan and returns its id. */
    private @NonNull UUID activeGroup() {
        Group g =
                Group.create(
                        "leader-1",
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
                CapabilityTestCalls.execute(
                        createNode,
                        new DagTools.CreateNode.Args("node-1", "Implement login", "coding", null));
        assertEquals("Node created: node-1 (skillset: coding). It is eligible for dispatch.", out);
    }

    @Test
    void createNodeMentionsDependenciesOnSuccess() throws Exception {
        UUID groupId = activeGroup();
        setContext("leader-1", groupId);
        CapabilityTestCalls.execute(
                createNode, new DagTools.CreateNode.Args("node-1", "a", "coding", null));
        String out =
                CapabilityTestCalls.execute(
                        createNode,
                        new DagTools.CreateNode.Args(
                                "node-2", "Test login", "testing", List.of("node-1")));
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
                                CapabilityTestCalls.execute(
                                        createNode,
                                        new DagTools.CreateNode.Args(
                                                "node-1", "a", "coding", List.of("node-9"))));
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
                                CapabilityTestCalls.execute(
                                        createNode,
                                        new DagTools.CreateNode.Args(
                                                "node-1", "a", "coding", null)));
        String message = ToolErrors.normalize(error.getMessage());
        assertTrue(message.startsWith("Node not created: no active group"), message);
    }

    @Test
    void removeNodeReturnsProseOnSuccess() throws Exception {
        UUID groupId = activeGroup();
        setContext("leader-1", groupId);
        CapabilityTestCalls.execute(
                createNode, new DagTools.CreateNode.Args("node-2", "a", "coding", null));
        String out =
                CapabilityTestCalls.execute(removeNode, new DagTools.RemoveNode.Args("node-2"));
        assertEquals("Node removed: node-2 (marked stale).", out);
    }

    @Test
    void removeNodeMapsDependentRefusalToInstructiveString() throws Exception {
        UUID groupId = activeGroup();
        setContext("leader-1", groupId);
        CapabilityTestCalls.execute(
                createNode, new DagTools.CreateNode.Args("node-1", "a", "coding", null));
        CapabilityTestCalls.execute(
                createNode,
                new DagTools.CreateNode.Args("node-3", "b", "testing", List.of("node-1")));
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                CapabilityTestCalls.execute(
                                        removeNode, new DagTools.RemoveNode.Args("node-1")));
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
}
