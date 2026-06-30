package top.focess.veto.group;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Part 2 group orchestration engine (leader_mate_topology.md, execution_dag.md). Drives a
 * {@link Group} through its lifecycle:
 *
 * <ol>
 *   <li><b>tick</b> the group: ingest new Blackboard messages, advance the DAG, dispatch
 *       dispatchable nodes.
 *   <li>When a node's deps are all {@link DagNode.NodeState#VERIFIED} and the node is {@link
 *       DagNode.NodeState#PENDING}, dispatch it to the assigned Mate via {@code TASK_DISPATCH} on
 *       the Blackboard.
 *   <li>When a Mate posts {@code ACCEPT}, mark the node {@code VERIFIED}.
 *   <li>When a Mate posts {@code FEEDBACK}, mark the node {@code FAILED} (Leader re-plans via
 *       {@link #replanFailed(String, String)}).
 *   <li>When a Mate posts a terminal {@code STATUS} (breaker trip), the node goes back to {@code
 *       PENDING} for re-assignment.
 *   <li>When the DAG has no more dispatchable nodes, mark the group {@code COMPLETE} and synthesize
 *       the final result.
 * </ol>
 *
 * <p>The engine is <b>deterministic</b>: given the same Blackboard input sequence, it produces the
 * same DAG state transitions. The Leader's "reasoning" — choosing which Mate to assign a node to,
 * deciding when to escalate, re-planning on FAILED — is the Leader's role (delegation_spawning.md
 * §3); the engine is the runtime over the Leader's decisions.
 */
@Component
public class GroupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GroupOrchestrator.class);

    private final @NonNull GroupRegistry registry;
    private final @NonNull Blackboard blackboard;
    private final @NonNull HeuristicLeader leader;

    /** Per-group ledger of last-seen turnSeq so each tick only processes new messages. */
    private final ConcurrentMap<UUID, Long> lastSeenSeq = new ConcurrentHashMap<>();

    /** A simple Mate-execution simulator (real Mates are agent loops; this is the harness). */
    private final MateExecutor mateExecutor = new MateExecutor();

    public GroupOrchestrator() {
        this(new GroupRegistry(), new Blackboard(), new HeuristicLeader());
    }

    public
    @NonNull
    GroupOrchestrator(@NonNull GroupRegistry registry, @NonNull Blackboard blackboard) {
        this(registry, blackboard, new HeuristicLeader());
    }

    @Autowired
    public GroupOrchestrator(
            GroupRegistry registry, Blackboard blackboard, HeuristicLeader leader) {
        this.registry = registry;
        this.blackboard = blackboard;
        this.leader = leader;
    }

    /** Construct with an LlmLeader (extracts the heuristic fallback for direct calls). */
    public
    @NonNull
    GroupOrchestrator(
            @NonNull GroupRegistry registry,
            @NonNull Blackboard blackboard,
            @NonNull LlmLeader llmLeader) {
        this.registry = registry;
        this.blackboard = blackboard;
        this.leader = llmLeader.heuristic();
    }

    public HeuristicLeader getLeader() {
        return leader;
    }

    /**
     * Run one orchestration tick on the group: ingest new Blackboard messages, advance the DAG,
     * dispatch dispatchable nodes, and emit outbound Blackboard messages. The updated group is
     * persisted back into the registry. Returns the updated group.
     */
    public @NonNull Group tick(@NonNull UUID groupId) {
        Group group = registry.get(groupId);
        if (group == null) {
            return null;
        }
        if (!group.isActive()) {
            return group;
        }

        // 0. Leader's "reasoning" step: assign Mates to PENDING nodes that have no Mate yet.
        //    (A real LLM Leader would author the DAG + assignment upfront; the HeuristicLeader
        //    assigns at tick time as a stand-in.)
        group = leader.assignMates(group);
        registry.put(group);

        // 1. Ingest new Blackboard messages addressed to the Leader.
        long seen = lastSeenSeq.getOrDefault(groupId, 0L);
        List<BlackboardMessage> newMessages = new ArrayList<>();
        for (BlackboardMessage m : blackboard.readAll(groupId)) {
            if (m.turnSeq() > seen) {
                newMessages.add(m);
            }
        }
        Group current = group;
        for (BlackboardMessage m : newMessages) {
            current = ingest(current, m);
            seen = Math.max(seen, m.turnSeq());
        }
        lastSeenSeq.put(groupId, seen);

        // 2. If the group is still active, dispatch dispatchable nodes.
        current = dispatch(current);

        // 3. If no more dispatchable nodes and no in-flight work, mark the group complete.
        current = maybeComplete(current);

        // Persist back to the registry so subsequent ticks see the updated state.
        registry.put(current);
        return current;
    }

    private Group ingest(Group group, BlackboardMessage message) {
        if (!"LEADER".equals(message.receiverId())) {
            return group; // not addressed to the Leader
        }
        return switch (message.type()) {
            case ACCEPT -> acceptNode(group, message);
            case FEEDBACK -> failNode(group, message);
            case STATUS -> handleStatus(group, message);
            case ARTIFACT_REF, LOG_REF, TASK_DISPATCH -> group; // Leader-side, no transition
        };
    }

    private Group acceptNode(Group group, BlackboardMessage message) {
        String nodeId = extractNodeId(message.payload());
        if (nodeId == null) {
            return group;
        }
        return markNode(group, nodeId, DagNode.NodeState.VERIFIED, message.payload());
    }

    private Group failNode(Group group, BlackboardMessage message) {
        String nodeId = extractNodeId(message.payload());
        if (nodeId == null) {
            return group;
        }
        return markNode(group, nodeId, DagNode.NodeState.FAILED, message.payload());
    }

    /**
     * Handle a Mate's terminal status (breaker trip / intercept). The Mate's current node goes back
     * to {@code PENDING} so the Leader can re-assign.
     */
    private Group handleStatus(Group group, BlackboardMessage message) {
        if (message.payload() == null || !message.payload().startsWith("terminal:")) {
            return group;
        }
        // terminal:<nodeId>:<reason> — find a RUNNING node for this Mate; if none, no-op.
        String[] parts = message.payload().split(":", 3);
        if (parts.length < 2) {
            return group;
        }
        String nodeId = parts[1];
        return markNode(
                group,
                nodeId,
                DagNode.NodeState.PENDING,
                "re-assigned: " + (parts.length > 2 ? parts[2] : "terminal"));
    }

    private Group markNode(Group group, String nodeId, DagNode.NodeState newState, String result) {
        ExecutionDag dag = group.dag();
        for (DagNode n : dag.nodes()) {
            if (!n.nodeId().equals(nodeId)) {
                continue;
            }
            DagNode.NodeState state = n.state();
            if (state == DagNode.NodeState.STALE) {
                // Stale nodes are not re-transitioned; the re-plan will replace them.
                continue;
            }
            DagNode updated =
                    new DagNode(
                            n.nodeId(),
                            n.description(),
                            n.assignedMateId(),
                            n.requiredSkillset(),
                            n.dependsOn(),
                            newState,
                            resultArtifact(result));
            return group.withDag(dag.withNode(nodeId, updated));
        }
        return group;
    }

    private static DagNode.NodeResult resultArtifact(String result) {
        if (result == null) {
            return new DagNode.ResultNone();
        }
        if (result.startsWith("accept:")) {
            return new DagNode.ResultArtifact(result.substring("accept:".length()).strip());
        }
        if (result.startsWith("feedback:")) {
            String body = result.substring("feedback:".length()).strip();
            return new DagNode.ResultFailure(body, List.of());
        }
        return new DagNode.ResultNone();
    }

    private static String extractNodeId(String payload) {
        if (payload == null) {
            return null;
        }
        int colon = payload.indexOf(':');
        if (colon < 0) {
            return payload.strip();
        }
        return payload.substring(0, colon).strip();
    }

    private Group dispatch(Group group) {
        ExecutionDag dag = group.dag();
        for (DagNode n : dag.dispatchable()) {
            if (n.assignedMateId() == null) {
                // Leader hasn't assigned this node yet. For the MVP the orchestrator
                // just marks it as STALE and expects the Leader to re-plan. A real Leader
                // would assign a Mate based on skillset.
                continue;
            }
            String task = n.description();
            String dispatchPayload = n.nodeId() + ":" + task;
            BlackboardMessage msg =
                    new BlackboardMessage(
                            UUID.randomUUID().toString(),
                            group.groupId(),
                            "LEADER",
                            n.assignedMateId(),
                            BlackboardMessage.MessageType.TASK_DISPATCH,
                            dispatchPayload,
                            0);
            blackboard.post(msg);
            ExecutionDag next =
                    dag.withNode(
                            n.nodeId(),
                            new DagNode(
                                    n.nodeId(),
                                    n.description(),
                                    n.assignedMateId(),
                                    n.requiredSkillset(),
                                    n.dependsOn(),
                                    DagNode.NodeState.RUNNING,
                                    new DagNode.ResultNone()));
            group = group.withDag(next);
        }
        return group;
    }

    /**
     * Strategic Pivot: re-plan a FAILED node. The Leader reasons about the failure and produces a
     * new node (or re-dispatches the same). The orchestrator exposes this hook so the Leader (or a
     * test) can drive re-planning.
     */
    public @NonNull Group replanFailed(@NonNull UUID groupId, @NonNull String nodeId) {
        Group group = registry.get(groupId);
        if (group == null) {
            return null;
        }
        return markNode(group, nodeId, DagNode.NodeState.PENDING, "re-plan");
    }

    private Group maybeComplete(Group group) {
        if (!group.isActive()) {
            return group;
        }
        // F11: An empty DAG (no nodes) means the Leader hasn't authored yet.
        // Do NOT complete the group — it should stay ACTIVE.
        if (group.dag().nodes().isEmpty()) {
            return group;
        }
        boolean anyInFlight = false;
        for (DagNode n : group.dag().nodes()) {
            if (n.state() == DagNode.NodeState.RUNNING) {
                anyInFlight = true;
                break;
            }
        }
        if (anyInFlight) {
            return group;
        }
        boolean anyOpen = false;
        for (DagNode n : group.dag().nodes()) {
            if (n.state() == DagNode.NodeState.PENDING || n.state() == DagNode.NodeState.FAILED) {
                anyOpen = true;
                break;
            }
        }
        if (anyOpen) {
            return group; // Leader needs to re-plan or assign
        }
        // All nodes VERIFIED → group complete.
        log.info("GroupOrchestrator: group {} complete (all nodes VERIFIED)", group.groupId());
        return group.withState(Group.GroupState.DISBANDED, Instant.now());
    }

    /**
     * The Mate-execution simulator. Real Mates are agent loops; for the MVP orchestration harness
     * this stands in: it turns a TASK_DISPATCH into a synthetic ACCEPT (after a configurable
     * work-cost). The Leader's tools can override this by posting messages directly to the
     * Blackboard.
     */
    public static class MateExecutor {
        /**
         * Simulate a Mate executing a task: return a synthetic Blackboard ACCEPT message. Real
         * Mates are agent loops; this is the harness fallback for tests + the "fire-and-forget"
         * stub path.
         */
        public BlackboardMessage accept(
                UUID groupId, String mateId, String nodeId, String artifactPath) {
            String payload =
                    nodeId + ":accept:" + (artifactPath == null ? "/artifact" : artifactPath);
            return new BlackboardMessage(
                    UUID.randomUUID().toString(),
                    groupId,
                    mateId,
                    "LEADER",
                    BlackboardMessage.MessageType.ACCEPT,
                    payload,
                    0);
        }
    }

    public MateExecutor getMateExecutor() {
        return mateExecutor;
    }

    /**
     * Build a one-shot synthetic ACCEPT for a node (testing + bootstrap). Posts the ACCEPT to the
     * Blackboard; the next {@link #tick} will VERIFY the node.
     */
    public void simulateAccept(
            @NonNull UUID groupId, @NonNull String mateId, @NonNull String nodeId) {
        BlackboardMessage m = mateExecutor.accept(groupId, mateId, nodeId, null);
        blackboard.post(m);
    }

    /** Build a one-shot synthetic FEEDBACK for a node (testing + bootstrap). */
    public void simulateFeedback(
            @NonNull UUID groupId,
            @NonNull String mateId,
            @NonNull String nodeId,
            @NonNull String feedback) {
        BlackboardMessage m =
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.FEEDBACK,
                        nodeId + ":feedback:" + (feedback == null ? "test failure" : feedback),
                        0);
        blackboard.post(m);
    }

    /**
     * Callback when a group is disbanded. This hook is called by {@link GroupSpawner} when a
     * group's lifecycle ends (either naturally complete or explicitly disbanded). For the MVP, this
     * is a no-op placeholder that logs the event; a real implementation would clean up resources,
     * persist final state, and notify downstream systems.
     */
    public void onGroupDisbanded(@NonNull UUID groupId) {
        log.info("GroupOrchestrator: group {} disbanded", groupId);
        // MVP: no additional cleanup needed; the registry already holds the final state.
    }
}
