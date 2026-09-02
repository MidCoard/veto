package top.focess.veto.group;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.group.GroupOrchestrator.NodeEdit;

/**
 * The Leader's node-authoring tools ({@code create_node} / {@code remove_node}). The Leader builds
 * the group's execution plan node by node - never as a submitted JSON blob - so the {@link
 * GroupOrchestrator} stays the DAG's source of truth: every call is one atomic, validated change
 * (duplicate id, unknown dependency, dependents on removal), and acyclicity holds by construction
 * (a node can only depend on nodes that already exist).
 *
 * <p>Both tools resolve the group from the {@link ToolCallContext} (the caller leads exactly one
 * group), so neither takes a {@code groupId} argument. Role gating (LEADER-only) is enforced by
 * tool availability - the Mate and STANDALONE personas are not offered these tools.
 */
public final class DagTools {

    private DagTools() {}

    /** Resolve the caller's group id from the tool call context, or null when not in a group. */
    private static UUID contextGroupId() {
        ToolCallContext ctx = ToolCallContextHolder.get();
        return ctx != null ? ctx.groupId() : null;
    }

    /** {@code create_node} — add a node to the group's execution plan. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Add a node to your group's execution plan - one discrete task with "
                            + "a required skillset.",
            behavior =
                    """
                    Adds one node to the execution plan. `dependsOn` may reference only existing, \
                    live nodes - the plan stays acyclic by construction. The node starts PENDING. \
                    On an orchestration tick after its dependencies are verified, the engine may \
                    reuse a Mate whose skillset label is exactly equal, or provision one and then \
                    dispatch the node. Skillsets are free-form scheduling labels; an unconfigured \
                    label uses the deployer's default Mate binding. Each plan \
                    mutation is validated atomically before it takes effect.
                    """,
            whenToUse =
                    """
                    Use `create_node` to build your plan node by node: one call per discrete \
                    task. Create dependencies before the nodes that need them - you author the \
                    plan from its foundations up. Also use it to extend the plan while the group \
                    is running.
                    """,
            whenNotToUse =
                    """
                    - Do not create a node before you have investigated enough to describe it \
                    concretely - vague nodes make vague work.
                    - Do not create nodes for work that needs no mate; synthesis is your job, \
                    not a node.
                    - Do not depend on a node that does not exist yet; create it first.
                    """,
            resultContract =
                    """
                    On success - one prose line per created node:
                      Node created: node-1 (skillset: coding). It is eligible for dispatch.
                    On rejection:
                      Node not created: <reason and what to do next>
                    """,
            errorsAndEdgeCases =
                    """
                    - Duplicate `nodeId` -> rejected; choose a unique id.
                    - `dependsOn` references an unknown or retired (stale) node -> rejected naming \
                    the id; create dependencies first.
                    - Blank `nodeId`, `description`, or `skillset` -> rejected.
                    """,
            security =
                    """
                    Agent tool (`RiskCategory.AGENT`). The Gateway returns `NotScreened`; each \
                    call is structurally validated by the engine before it takes effect. \
                    Leader-only.
                    """,
            examples = {
                "{\"nodeId\": \"node-1\", \"description\": \"Implement JWT login in UserService\", \"skillset\": \"coding\"}",
                "{\"nodeId\": \"node-2\", \"description\": \"Test the login flow\", \"skillset\": \"testing\", \"dependsOn\": [\"node-1\"]}",
                "{\"nodeId\": \"node-2\", \"description\": \"Verify\", \"skillset\": \"testing\", \"dependsOn\": [\"node-9\"]}"
            },
            returnExamples = {
                "Node created: node-1 (skillset: coding). It is eligible for dispatch.",
                "Node created: node-2 (skillset: testing, depends on: node-1). It becomes eligible after its dependencies verify."
            })
    public static final class CreateNode implements AgentTool<CreateNode.Args> {

        private final @NonNull GroupOrchestrator orchestrator;

        public CreateNode(@NonNull GroupOrchestrator orchestrator) {
            this.orchestrator = orchestrator;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("New node's id (unique within the plan, e.g. 'node-1').")
                        @NonNull String nodeId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "What the node does - concrete enough for a mate to execute without asking.")
                        @NonNull String description,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Free-form scheduling label (e.g. 'coding', 'testing'); exact matches reuse a Mate, and unconfigured labels use the default Mate binding.")
                        @NonNull String skillset,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Ids of existing nodes that must verify before this one dispatches; "
                                        + "omit for a root node.")
                        List<String> dependsOn) {}

        @Override
        public @NonNull String getName() {
            return "create_node";
        }

        @Override
        public @NonNull String getDescription() {
            ToolDoc doc =
                    ToolDocs.nonNullClass(Args.class)
                            .getAnnotation(ToolDocs.nonNullClass(ToolDoc.class));
            return (doc != null && !doc.description().isEmpty()) ? doc.description() : "";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            UUID groupId = contextGroupId();
            if (groupId == null) {
                return ToolErrors.failure(
                        "Node not created: no active group in your context. create_node is "
                                + "a Leader tool inside a group.");
            }
            String nodeId = args.nodeId().strip();
            String description = args.description().strip();
            String skillset = args.skillset().strip();
            Set<String> deps =
                    args.dependsOn() == null ? Set.of() : new LinkedHashSet<>(args.dependsOn());
            NodeEdit edit = orchestrator.addNode(groupId, nodeId, description, skillset, deps);
            if (edit instanceof NodeEdit.Rejected r) {
                return ToolErrors.failure("Node not created: " + r.reason());
            }
            if (deps.isEmpty()) {
                return "Node created: "
                        + nodeId
                        + " (skillset: "
                        + skillset
                        + "). It is eligible for dispatch.";
            }
            return "Node created: "
                    + nodeId
                    + " (skillset: "
                    + skillset
                    + ", depends on: "
                    + String.join(", ", deps)
                    + "). It becomes eligible after its dependencies verify.";
        }
    }

    /** {@code remove_node} — retire a node from the group's plan (marked STALE). */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Retire a node from your group's plan - re-planning marks it stale "
                            + "rather than deleting it.",
            behavior =
                    """
                    Marks the node STALE and keeps it in the plan record for audit. New nodes cannot \
                    depend on it, and live dependents must be removed or re-planned first. This call \
                    does not stop a running Mate or clear the recorded assignment.
                    """,
            whenToUse =
                    """
                    Use `remove_node` when re-planning makes a node obsolete - a strategic \
                    pivot, a task that turned out unnecessary, or a failed node you are replacing \
                    with a different approach.
                    """,
            whenNotToUse =
                    """
                    - Do not remove a VERIFIED node; verified work is checkpointed and stays.
                    - Do not remove a node others still depend on; re-plan or remove the \
                    dependents first (the error names them).
                    - Do not remove a node as a reaction to a single failure - the engine already \
                    routes retries; removal is for plan-level changes.
                    """,
            resultContract =
                    """
                    On success:
                      Node removed: node-2 (marked stale).
                    On refusal:
                      Node not removed: node-3 depends on node-1. Remove or re-plan it first.
                    """,
            errorsAndEdgeCases =
                    """
                    - Unknown `nodeId` -> `Node not removed: node not found: <id>`.
                    - Live dependents exist -> refused, naming the dependents.
                    - Already stale or VERIFIED -> not removed; verified work remains checkpointed.
                    """,
            security =
                    """
                    Agent tool (`RiskCategory.AGENT`). The Gateway returns `NotScreened`; the \
                    engine validates the removal before it takes effect. Leader-only.
                    """,
            examples = {"{\"nodeId\": \"node-2\"}", "{\"nodeId\": \"node-1\"}"},
            returnExamples = {"Node removed: node-2 (marked stale)."})
    public static final class RemoveNode implements AgentTool<RemoveNode.Args> {

        private final @NonNull GroupOrchestrator orchestrator;

        public RemoveNode(@NonNull GroupOrchestrator orchestrator) {
            this.orchestrator = orchestrator;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The id of the node to retire.")
                        @NonNull String nodeId) {}

        @Override
        public @NonNull String getName() {
            return "remove_node";
        }

        @Override
        public @NonNull String getDescription() {
            ToolDoc doc =
                    ToolDocs.nonNullClass(Args.class)
                            .getAnnotation(ToolDocs.nonNullClass(ToolDoc.class));
            return (doc != null && !doc.description().isEmpty()) ? doc.description() : "";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            UUID groupId = contextGroupId();
            if (groupId == null) {
                return ToolErrors.failure(
                        "Node not removed: no active group in your context. remove_node is "
                                + "a Leader tool inside a group.");
            }
            String nodeId = args.nodeId().strip();
            NodeEdit edit = orchestrator.removeNode(groupId, nodeId);
            if (edit instanceof NodeEdit.Rejected r) {
                return ToolErrors.failure("Node not removed: " + r.reason());
            }
            return "Node removed: " + nodeId + " (marked stale).";
        }
    }
}
