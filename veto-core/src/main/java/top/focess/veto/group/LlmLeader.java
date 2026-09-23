package top.focess.veto.group;

import static top.focess.veto.util.LogValues.safe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentResult;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.loop.PromptCompiler;

/**
 * LLM-backed Leader that wraps an {@link Agent}. It authors the DAG, assigns Mates, reasons over
 * feedback, and triggers a strategic pivot when necessary. The agent receives the current group
 * state, its leader role, and a structured output schema.
 *
 * <p>Falls back to {@link HeuristicLeader} when the underlying agent is {@code null} (no LLM
 * configured) or the prompt round-trip fails. The composition is a Strategy pattern — the
 * orchestrator holds a {@link HeuristicLeader} field; this class delegates to it for the structural
 * methods (skillset-based assignment, retry-with-backoff) and adds the LLM-driven authoring +
 * reasoning layer on top.
 *
 * <p>Prompt template (simplified):
 *
 * <pre>
 * You are the Leader of group {groupId}.
 * Mates: {mates}
 * DAG nodes: {dag}
 * Recent Blackboard messages: {messages}
 *
 * Decide: (1) which PENDING nodes to assign to which Mate; (2) whether to pivot (mark STALE)
 * given the per-Mate message count vs ACCEPT ratio; (3) how to re-dispatch FAILED nodes.
 * Return JSON: {"assignments": [{"nodeId": "...", "mateId": "..."}],
 *               "pivot": false, "replan": [{"nodeId": "...", "instruction": "..."}]}
 * </pre>
 *
 * <p>The LLM response is parsed leniently (any valid JSON is honored; on parse failure the
 * heuristic leader's decisions win).
 */
@Component
public class LlmLeader {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.group.LlmLeader");

    private final Agent leaderAgent;
    private final @NonNull HeuristicLeader fallback;

    public LlmLeader() {
        this(null, new HeuristicLeader());
    }

    public LlmLeader(@NonNull AgentPersona leaderPersona, @NonNull Agent leaderAgent) {
        this.leaderAgent = leaderAgent;
        this.fallback = new HeuristicLeader();
    }

    /** Construct with a pre-built heuristic fallback (for tests). */
    public LlmLeader(Agent leaderAgent, @NonNull HeuristicLeader fallback) {
        this.leaderAgent = leaderAgent;
        this.fallback = fallback;
    }

    /**
     * Author the DAG from the contextBrief: have the Leader agent investigate + draft the initial
     * node list. Returns the fallback's linear DAG if the LLM call fails.
     */
    public @NonNull ExecutionDag authorDag(@NonNull UUID groupId, @NonNull String contextBrief) {
        if (leaderAgent == null) {
            return ExecutionDag.linear(groupId, List.of("n1"));
        }
        try {
            String prompt = buildAuthorPrompt(groupId, contextBrief);
            leaderAgent.submit(prompt);
            AgentResult result = leaderAgent.await(Duration.ofSeconds(30));
            if (result.success()) {
                ExecutionDag parsed = parseDagFromJson(groupId, result.message());
                if (parsed != null && !parsed.nodes().isEmpty()) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.warn(
                    "LlmLeader: DAG authoring failed, falling back to heuristic: {}",
                    safe(e.getMessage()));
        }
        return ExecutionDag.linear(groupId, List.of("n1"));
    }

    /**
     * Decide whether to pivot: ask the Leader agent to reason over the per-Mate message count +
     * Blackboard state. Falls back to {@link HeuristicLeader#shouldPivot} on failure.
     */
    public boolean shouldPivot(
            @NonNull Group group, int perMateMessageCount, double contextSaturationRatio) {
        if (fallback.shouldPivot(group, perMateMessageCount, contextSaturationRatio)) {
            return true; // heuristic said yes — trust it
        }
        if (leaderAgent == null) {
            return false;
        }
        try {
            String prompt = buildPivotPrompt(group, perMateMessageCount, contextSaturationRatio);
            leaderAgent.submit(prompt);
            AgentResult result = leaderAgent.await(Duration.ofSeconds(15));
            if (result.success()) {
                // Parse the {pivot: bool, ...} JSON; do NOT string-match (a prior version
                // matched "PIVOT" inside the key name even when the value was false).
                return parsePivotDecision(result.message());
            }
        } catch (Exception e) {
            log.debug("LlmLeader: pivot reasoning failed: {}", safe(e.getMessage()));
        }
        return false;
    }

    /**
     * Lenient JSON parser for the pivot-decision response. Accepts:
     *
     * <ul>
     *   <li>{@code {"pivot": true|false, ...}} — the documented schema
     *   <li>any JSON containing a top-level boolean {@code pivot} field
     * </ul>
     *
     * <p>Returns false on parse failure or a missing field. The LLM can only raise, never lower,
     * the deterministic heuristic's signal; this method is called only after that heuristic
     * returned false.
     */
    static boolean parsePivotDecision(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        try {
            ObjectMapper m = new ObjectMapper();
            JsonNode root = m.readTree(response);
            if (root == null || !root.isObject()) {
                return false;
            }
            JsonNode pivot = root.get("pivot");
            if (pivot == null || pivot.isNull()) {
                return false;
            }
            if (pivot.isBoolean()) {
                return pivot.booleanValue();
            }
            // Tolerate stringly-typed booleans ("true"/"false"/"yes"/"no").
            String s = pivot.asText("").toLowerCase();
            return s.equals("true") || s.equals("yes");
        } catch (Exception e) {
            return false;
        }
    }

    /** Delegate skillset-based Mate assignment to the heuristic (it's structural). */
    public @NonNull Group assignMates(@NonNull Group group) {
        return fallback.assignMates(group);
    }

    /**
     * Reasoning-buffer saturation in [0.0, 1.0]. Delegates to the heuristic; the real
     * implementation (LLM-backed Leader) will query PromptCompiler for the active buffer's
     * fraction-full when the agent is wired.
     */
    public double contextSaturation(@NonNull Group group) {
        return fallback.contextSaturation(group);
    }

    /** Delegate the retry/backoff escalation to the heuristic. */
    public @NonNull Group escalate(
            @NonNull Group group, @NonNull String nodeId, @NonNull String feedback) {
        return fallback.escalate(group, nodeId, feedback);
    }

    /** Delegate the re-plan to the heuristic. */
    public @NonNull Group pivot(@NonNull Group group) {
        return fallback.pivot(group);
    }

    /** Delegate replanFailed to the heuristic. */
    public @NonNull Group replanFailed(@NonNull Group group, @NonNull String nodeId) {
        return fallback.escalate(group, nodeId, "re-plan");
    }

    /** Access the heuristic fallback (for tests). */
    public @NonNull HeuristicLeader heuristic() {
        return fallback;
    }

    /** Heuristic prompt: "investigate + author a DAG from this contextBrief." */
    private static @NonNull String buildAuthorPrompt(@NonNull UUID groupId, String contextBrief) {
        return PromptCompiler.compileText(
                "leader-author",
                Map.of("groupId", groupId, "brief", contextBrief == null ? "" : contextBrief));
    }

    /** Heuristic prompt: "should we pivot?" */
    private static @NonNull String buildPivotPrompt(
            @NonNull Group group, int perMateMessageCount, double saturation) {
        return PromptCompiler.compileText(
                "leader-pivot",
                Map.of(
                        "groupId",
                        group.groupId(),
                        "messageCount",
                        perMateMessageCount,
                        "saturation",
                        saturation,
                        "mates",
                        group.mates(),
                        "nodes",
                        group.dag().nodes().stream().map(GroupPromptInputs::node).toList()));
    }

    /**
     * Lenient DAG JSON parser — best-effort. Preserves the LLM's authored structure ({@code
     * nodeId}, {@code description}, {@code skillset}, {@code dependsOn}) so the orchestrator can
     * dispatch in parallel and route by skillset. A prior version discarded everything but {@code
     * nodeId} and fell back to {@link ExecutionDag#linear}, which hardcoded a serial chain and
     * {@code skillset="coding"} for every node — making the LLM-driven DAG authoring a structural
     * no-op.
     *
     * <p>Returns null on parse failure or when no nodes were authored.
     */
    static ExecutionDag parseDagFromJson(@NonNull UUID groupId, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ObjectMapper m = new ObjectMapper();
            JsonNode root = m.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode nodesNode = root.get("nodes");
            if (nodesNode == null || !nodesNode.isArray() || nodesNode.isEmpty()) {
                return null;
            }
            List<DagNode> nodes = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();
            for (JsonNode n : nodesNode) {
                if (n == null || !n.isObject()) {
                    continue;
                }
                String nodeId = textOrNull(n, "nodeId");
                if (nodeId == null || nodeId.isBlank() || !seenIds.add(nodeId)) {
                    continue;
                }
                String description = textOrNull(n, "description");
                if (description == null) {
                    description = "node " + nodeId;
                }
                String skillset = textOrNull(n, "skillset");
                if (skillset == null || skillset.isBlank()) {
                    skillset = "coding";
                }
                Set<String> dependsOn = new LinkedHashSet<>();
                JsonNode deps = n.get("dependsOn");
                if (deps != null && deps.isArray()) {
                    for (JsonNode d : deps) {
                        if (d != null && d.isTextual()) {
                            String dep = d.asText();
                            // Only keep dependencies that reference a node the LLM also
                            // authored; otherwise we'd build a DAG with phantom edges.
                            if (seenIds.contains(dep) || refersToLaterNode(dep, nodesNode, n)) {
                                dependsOn.add(dep);
                            }
                        }
                    }
                }
                nodes.add(
                        new DagNode(
                                nodeId,
                                description,
                                null, // Mate assigned by the orchestrator at dispatch time
                                skillset,
                                dependsOn,
                                DagNode.NodeState.PENDING,
                                new DagNode.ResultNone()));
            }
            if (nodes.isEmpty()) {
                return null;
            }
            return new ExecutionDag(groupId, nodes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(@NonNull JsonNode obj, @NonNull String field) {
        JsonNode v = obj.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            String s = v.asText();
            return s.isBlank() ? null : s;
        }
        return v.asText();
    }

    /**
     * Allow a forward dependency if the LLM authored that node later in the same array. This
     * handles the common case where the LLM lists nodes out of order but the dependency is still
     * real (it will be resolvable once all nodes are in {@code seenIds}).
     */
    private static boolean refersToLaterNode(
            @NonNull String dep, @NonNull JsonNode nodesNode, @NonNull JsonNode current) {
        for (JsonNode other : nodesNode) {
            if (other == current) {
                continue;
            }
            JsonNode idNode = other == null ? null : other.get("nodeId");
            if (idNode != null && idNode.isTextual() && dep.equals(idNode.asText())) {
                return true;
            }
        }
        return false;
    }
}
