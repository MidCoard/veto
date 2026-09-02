package top.focess.veto.group;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Drives a {@link Group} through its leader-and-mate lifecycle:
 *
 * <ol>
 *   <li><b>tick</b> the group: ingest new Blackboard messages, advance the DAG, dispatch
 *       dispatchable nodes.
 *   <li>When a node's deps are all {@link DagNode.NodeState#VERIFIED} and the node is {@link
 *       DagNode.NodeState#PENDING}, dispatch it to the assigned Mate via {@code TASK_DISPATCH} on
 *       the Blackboard.
 *   <li>When a Mate posts {@code ACCEPT}, mark the node {@code VERIFIED}.
 *   <li>When a Mate posts {@code FEEDBACK}, mark the node {@code FAILED} (Leader re-plans via
 *       {@link #replanFailed(UUID, String)}).
 *   <li>When a Mate posts a terminal {@code STATUS} (breaker trip), the node goes back to {@code
 *       PENDING} for re-assignment.
 *   <li>When the DAG has no more dispatchable nodes, mark the group {@code COMPLETE} and synthesize
 *       the final result.
 * </ol>
 *
 * <p>The engine is <b>deterministic</b>: given the same Blackboard input sequence, it produces the
 * same DAG state transitions. Choosing which Mate receives a node, deciding when to escalate, and
 * re-planning failed work remain the Leader's responsibility; the engine applies those decisions.
 */
@Component
public class GroupOrchestrator {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.group.GroupOrchestrator");

    private final @NonNull GroupRegistry registry;
    private final @NonNull Blackboard blackboard;
    private final @NonNull HeuristicLeader leader;

    /**
     * Lazy Mate provisioning (Model B). When wired (production), the orchestrator provisions a Mate
     * on demand for a dispatchable node whose skillset has no Mate yet - instead of the Leader
     * spawning Mates upfront. Null in the test constructors, which fall back to {@link
     * HeuristicLeader#assignMates} + pre-assigned Mates.
     */
    private final MateProvisioner provisioner;

    /** Per-group ledger of last-seen turnSeq so each tick only processes new messages. */
    private final @NonNull ConcurrentMap<UUID, Long> lastSeenSeq = new ConcurrentHashMap<>();

    /**
     * Per-group tick lock (F4). Serializes concurrent {@link #tick} calls on the same groupId so
     * the lastSeenSeq read-modify-write and the ingest/dispatch/maybeComplete sequence are atomic
     * per-group. Different groups can tick concurrently; only same-group ticks are serialized.
     */
    private final @NonNull ConcurrentMap<UUID, ReentrantLock> tickLocks = new ConcurrentHashMap<>();

    /** A simple Mate-execution simulator (real Mates are agent loops; this is the harness). */
    private final @NonNull MateExecutor mateExecutor = new MateExecutor();

    public GroupOrchestrator() {
        this(new GroupRegistry(), new Blackboard(), new HeuristicLeader(), null);
    }

    public GroupOrchestrator(@NonNull GroupRegistry registry, @NonNull Blackboard blackboard) {
        this(registry, blackboard, new HeuristicLeader(), null);
    }

    @Autowired
    public GroupOrchestrator(
            @NonNull GroupRegistry registry,
            @NonNull Blackboard blackboard,
            @NonNull HeuristicLeader leader,
            @Lazy MateProvisioner provisioner) {
        this.registry = registry;
        this.blackboard = blackboard;
        this.leader = leader;
        this.provisioner = provisioner;
    }

    /** Construct with an LlmLeader (extracts the heuristic fallback for direct calls). */
    public GroupOrchestrator(
            @NonNull GroupRegistry registry,
            @NonNull Blackboard blackboard,
            @NonNull LlmLeader llmLeader) {
        this.registry = registry;
        this.blackboard = blackboard;
        this.leader = llmLeader.heuristic();
        this.provisioner = null;
    }

    public @NonNull HeuristicLeader getLeader() {
        return leader;
    }

    /**
     * Lazy Mate provisioning: create + start a Mate of the given skillset for the group and return
     * its id. The orchestrator calls this in {@link #dispatch} for a dispatchable node whose
     * skillset has no Mate yet. The provisioner must NOT update the group registry (the
     * orchestrator owns that, atomically, after provisioning) - it only creates + starts the Mate
     * agent.
     */
    @FunctionalInterface
    public interface MateProvisioner {
        @NonNull String provision(@NonNull UUID groupId, @NonNull String skillset);
    }

    /**
     * The outcome of a {@code create_node} / {@code remove_node} structural edit. The orchestrator
     * is the DAG's single authoritative writer: every edit is one atomic, validated change, and the
     * tool routes on this return value (never on a state snapshot captured before the call).
     */
    public sealed interface NodeEdit {

        /** The edit was applied. */
        record Applied() implements NodeEdit {}

        /** The edit was rejected; {@code reason} explains why and what to do next. */
        record Rejected(@NonNull String reason) implements NodeEdit {}
    }

    /** Run {@code op} under the per-group lock (shared with {@link #tick}). */
    private <T> T withGroupLock(@NonNull UUID groupId, @NonNull Supplier<T> op) {
        ReentrantLock lock = tickLocks.computeIfAbsent(groupId, k -> new ReentrantLock());
        lock.lock();
        try {
            return op.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Add a node to the group's plan (the {@code create_node} op). Atomic and validated: the group
     * must be active, the id unique, and every dependency must reference an existing, live (not
     * STALE) node - so the plan stays acyclic by construction. The new node starts PENDING; the
     * next tick dispatches it once its dependencies verify.
     */
    public @NonNull NodeEdit addNode(
            @NonNull UUID groupId,
            @NonNull String nodeId,
            @NonNull String description,
            @NonNull String skillset,
            @NonNull Set<String> dependsOn) {
        return withGroupLock(
                groupId,
                () -> {
                    Group group = registry.get(groupId);
                    if (group == null) {
                        return new NodeEdit.Rejected("group not found: " + groupId);
                    }
                    if (!group.isActive()) {
                        return new NodeEdit.Rejected("group is no longer active");
                    }
                    if (nodeId.isBlank()) {
                        return new NodeEdit.Rejected("blank node id");
                    }
                    if (description.isBlank()) {
                        return new NodeEdit.Rejected("blank description");
                    }
                    if (skillset.isBlank()) {
                        return new NodeEdit.Rejected("blank skillset");
                    }
                    ExecutionDag dag = group.dag();
                    if (dag.nodeIds().contains(nodeId)) {
                        return new NodeEdit.Rejected(
                                nodeId + " already exists. Choose a unique id.");
                    }
                    for (String dep : dependsOn) {
                        DagNode d = null;
                        for (DagNode n : dag.nodes()) {
                            if (n.nodeId().equals(dep)) {
                                d = n;
                                break;
                            }
                        }
                        if (d == null) {
                            return new NodeEdit.Rejected(
                                    "unknown dependency "
                                            + dep
                                            + ". Create dependencies before the nodes that need"
                                            + " them.");
                        }
                        if (d.state() == DagNode.NodeState.STALE) {
                            return new NodeEdit.Rejected(
                                    "dependency "
                                            + dep
                                            + " was retired (stale). Re-plan around it.");
                        }
                    }
                    DagNode node = DagNode.pending(nodeId, description, skillset, dependsOn);
                    List<DagNode> next = new ArrayList<>(dag.nodes());
                    next.add(node);
                    registry.put(group.withDag(dag.withNodes(next)));
                    return new NodeEdit.Applied();
                });
    }

    /**
     * Retire a node from the group's plan (the {@code remove_node} op): marks it STALE (kept in the
     * plan record for audit; never dispatched again). Refused when the node is unknown, already
     * STALE, VERIFIED (checkpointed), or still has live dependents (named in the rejection).
     */
    public @NonNull NodeEdit removeNode(@NonNull UUID groupId, @NonNull String nodeId) {
        return withGroupLock(
                groupId,
                () -> {
                    Group group = registry.get(groupId);
                    if (group == null) {
                        return new NodeEdit.Rejected("group not found: " + groupId);
                    }
                    if (!group.isActive()) {
                        return new NodeEdit.Rejected("group is no longer active");
                    }
                    ExecutionDag dag = group.dag();
                    DagNode target = null;
                    List<String> liveDependents = new ArrayList<>();
                    for (DagNode n : dag.nodes()) {
                        if (n.nodeId().equals(nodeId)) {
                            target = n;
                        } else if (n.dependsOn().contains(nodeId)
                                && n.state() != DagNode.NodeState.STALE
                                && n.state() != DagNode.NodeState.VERIFIED) {
                            liveDependents.add(n.nodeId());
                        }
                    }
                    if (target == null) {
                        return new NodeEdit.Rejected("node not found: " + nodeId);
                    }
                    if (target.state() == DagNode.NodeState.STALE) {
                        return new NodeEdit.Rejected(nodeId + " is already retired (stale).");
                    }
                    if (target.state() == DagNode.NodeState.VERIFIED) {
                        return new NodeEdit.Rejected(
                                nodeId + " is verified; verified work is checkpointed and stays.");
                    }
                    if (!liveDependents.isEmpty()) {
                        return new NodeEdit.Rejected(
                                String.join(", ", liveDependents)
                                        + " depends on "
                                        + nodeId
                                        + ". Remove or re-plan it first.");
                    }
                    DagNode stale =
                            new DagNode(
                                    target.nodeId(),
                                    target.description(),
                                    target.assignedMateId(),
                                    target.requiredSkillset(),
                                    target.dependsOn(),
                                    DagNode.NodeState.STALE,
                                    target.result(),
                                    target.retryCount());
                    registry.put(group.withDag(dag.withNode(nodeId, stale)));
                    return new NodeEdit.Applied();
                });
    }

    /**
     * Run one orchestration tick on the group: ingest new Blackboard messages, advance the DAG,
     * dispatch dispatchable nodes, and emit outbound Blackboard messages. The updated group is
     * persisted back into the registry. Returns the updated group.
     *
     * <p>Concurrent ticks on the same groupId are serialized via a per-group lock (F4) so the
     * lastSeenSeq read-modify-write and the ingest/dispatch/maybeComplete sequence are atomic.
     * Different groups can tick concurrently.
     */
    public Group tick(@NonNull UUID groupId) {
        return withGroupLock(groupId, () -> tickInner(groupId));
    }

    /** Inner tick logic — called under the per-group lock. */
    private Group tickInner(@NonNull UUID groupId) {
        Group group = registry.get(groupId);
        if (group == null) {
            return null;
        }
        if (!group.isActive()) {
            return group;
        }

        // 0. Leader's "reasoning" step: assign Mates to PENDING nodes that have no Mate yet. Only
        //    used when no lazy provisioner is wired (the test path) - the HeuristicLeader assigns
        // at
        //    tick time as a stand-in. In production (provisioner wired), Mates are provisioned
        // lazily
        //    in dispatch() instead, so this step is skipped.
        if (provisioner == null) {
            group = leader.assignMates(group);
            registry.put(group);
        }

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

    private @NonNull Group ingest(@NonNull Group group, @NonNull BlackboardMessage message) {
        if (!"LEADER".equals(message.receiverId())) {
            return group; // not addressed to the Leader
        }
        return switch (message.type()) {
            case ACCEPT -> transitionFromMate(group, message, DagNode.NodeState.VERIFIED);
            case FEEDBACK -> transitionFromMate(group, message, DagNode.NodeState.FAILED);
            case STATUS -> handleStatus(group, message);
            case ARTIFACT_REF, LOG_REF, TASK_DISPATCH -> group; // Leader-side, no transition
        };
    }

    private @NonNull Group transitionFromMate(
            @NonNull Group group,
            @NonNull BlackboardMessage message,
            DagNode.@NonNull NodeState newState) {
        String nodeId = extractNodeId(message.payload());
        if (nodeId == null) {
            return group;
        }
        return markNodeFromMate(group, nodeId, newState, message);
    }

    /**
     * Handle a Mate's terminal status (breaker trip / intercept). The Mate's current node goes back
     * to {@code PENDING} so the Leader can re-assign.
     */
    private @NonNull Group handleStatus(@NonNull Group group, @NonNull BlackboardMessage message) {
        if (!message.payload().startsWith("terminal:")) {
            return group;
        }
        // terminal:<nodeId>:<reason> — find a RUNNING node for this Mate; if none, no-op.
        String[] parts = message.payload().split(":", 3);
        if (parts.length < 2) {
            return group;
        }
        String nodeId = parts[1];
        return markNodeFromMate(group, nodeId, DagNode.NodeState.PENDING, message);
    }

    /** Only the currently assigned Mate may transition a RUNNING node. */
    private @NonNull Group markNodeFromMate(
            @NonNull Group group,
            @NonNull String nodeId,
            DagNode.@NonNull NodeState newState,
            @NonNull BlackboardMessage message) {
        ExecutionDag dag = group.dag();
        for (DagNode node : dag.nodes()) {
            if (!node.nodeId().equals(nodeId)) {
                continue;
            }
            String assignedMateId = node.assignedMateId();
            if (node.state() != DagNode.NodeState.RUNNING
                    || assignedMateId == null
                    || !assignedMateId.equals(message.senderId())) {
                log.warn(
                        "Ignored {} for node {} from unassigned sender {}",
                        message.type(),
                        nodeId,
                        message.senderId());
                return group;
            }
            DagNode updated =
                    new DagNode(
                            node.nodeId(),
                            node.description(),
                            node.assignedMateId(),
                            node.requiredSkillset(),
                            node.dependsOn(),
                            newState,
                            resultFromMate(message),
                            node.retryCount());
            return group.withDag(dag.withNode(nodeId, updated));
        }
        return group;
    }

    private @NonNull Group markNode(
            @NonNull Group group, @NonNull String nodeId, DagNode.@NonNull NodeState newState) {
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
                            new DagNode.ResultNone(),
                            n.retryCount());
            return group.withDag(dag.withNode(nodeId, updated));
        }
        return group;
    }

    private static DagNode.@NonNull NodeResult resultFromMate(@NonNull BlackboardMessage message) {
        String[] parts = message.payload().split(":", 3);
        if (message.type() == BlackboardMessage.MessageType.ACCEPT
                && parts.length == 3
                && "accept-base64".equals(parts[1])) {
            try {
                String summary =
                        new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                return new DagNode.ResultSuccess(summary);
            } catch (IllegalArgumentException e) {
                return new DagNode.ResultSuccess("Mate completed without a decodable report.");
            }
        }
        if (message.type() == BlackboardMessage.MessageType.FEEDBACK && parts.length == 3) {
            return new DagNode.ResultFailure(parts[2].strip(), List.of());
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

    private @NonNull Group dispatch(@NonNull Group group) {
        ExecutionDag dag = group.dag();
        for (DagNode n : dag.dispatchable()) {
            String mateId = n.assignedMateId();
            if (mateId == null) {
                if (provisioner == null) {
                    // No lazy provisioning (test path); assignMates handles assignment, so an
                    // unassigned dispatchable node just waits for the next tick.
                    continue;
                }
                // Reuse an existing Mate of the required skillset if one exists; otherwise
                // provision
                // one lazily. The provisioner starts the Mate agent but does NOT touch the registry
                // - the group update below (withMate + node assignment) is persisted atomically at
                // the end of the tick, so no Mate registration is lost to a stale-snapshot write.
                mateId = existingMateOfSkillset(group, n.requiredSkillset());
                if (mateId == null) {
                    mateId = provisioner.provision(group.groupId(), n.requiredSkillset());
                    group = group.withMate(mateId, n.requiredSkillset());
                    dag = group.dag();
                }
            }
            String task = n.description();
            String dispatchPayload = n.nodeId() + ":" + task;
            BlackboardMessage msg =
                    new BlackboardMessage(
                            UUID.randomUUID().toString(),
                            group.groupId(),
                            "LEADER",
                            mateId,
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
                                    mateId,
                                    n.requiredSkillset(),
                                    n.dependsOn(),
                                    DagNode.NodeState.RUNNING,
                                    new DagNode.ResultNone()));
            group = group.withDag(next);
            dag = next;
        }
        return group;
    }

    /** Returns the id of any existing Mate of the given skillset, or null if none exists. */
    private static String existingMateOfSkillset(@NonNull Group group, String skillset) {
        if (skillset == null || skillset.isBlank()) {
            return null;
        }
        for (var entry : group.mates().entrySet()) {
            if (skillset.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Strategic Pivot: re-plan a FAILED node. The Leader reasons about the failure and produces a
     * new node (or re-dispatches the same). The orchestrator exposes this hook so the Leader (or a
     * test) can drive re-planning.
     */
    public Group replanFailed(@NonNull UUID groupId, @NonNull String nodeId) {
        Group group = registry.get(groupId);
        if (group == null) {
            return null;
        }
        return markNode(group, nodeId, DagNode.NodeState.PENDING);
    }

    private @NonNull Group maybeComplete(@NonNull Group group) {
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
        // All nodes VERIFIED → completed, but not yet disbanded. The Leader must first inspect the
        // reports and call disband_group so its persona can reverse-transform to STANDALONE.
        log.info("GroupOrchestrator: group {} complete (all nodes VERIFIED)", group.groupId());
        return group.withState(Group.GroupState.COMPLETED, Instant.now());
    }

    /**
     * The Mate-execution simulator. Real Mates are agent loops; this testable stand-in turns a
     * TASK_DISPATCH into a synthetic ACCEPT after a configurable work cost. The Leader's tools can
     * override this by posting messages directly to the Blackboard.
     */
    public static class MateExecutor {
        /**
         * Simulate a Mate executing a task: return a synthetic Blackboard ACCEPT message. Real
         * Mates are agent loops; this is the harness fallback for tests + the "fire-and-forget"
         * stub path.
         */
        public @NonNull BlackboardMessage accept(
                @NonNull UUID groupId,
                @NonNull String mateId,
                @NonNull String nodeId,
                String artifactPath) {
            String summary =
                    artifactPath == null ? "Synthetic Mate completion." : artifactPath.strip();
            String payload =
                    nodeId
                            + ":accept-base64:"
                            + Base64.getEncoder()
                                    .encodeToString(summary.getBytes(StandardCharsets.UTF_8));
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

    public @NonNull MateExecutor getMateExecutor() {
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
                        nodeId + ":feedback:" + feedback,
                        0);
        blackboard.post(m);
    }

    /**
     * Callback when a group is disbanded. This hook is called by {@link GroupSpawner} when a
     * group's lifecycle ends, either naturally or through explicit disbanding. The current hook
     * records the event but does not perform resource cleanup, persistence, or notifications.
     */
    public void onGroupDisbanded(@NonNull UUID groupId) {
        log.info("GroupOrchestrator: group {} disbanded", groupId);
        // The registry already holds the final state; no additional cleanup is required.
    }
}
