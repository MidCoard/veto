package top.focess.veto.group;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.GroupControlCapability;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.GroupControlTool;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolResultFormat;
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

    /** {@code create_node} — add a node to the group's execution plan. */
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Add a node to your group's execution plan - one discrete task with "
                            + "a required skillset.",
            behavior =
                    """
                    Adds one node to the execution plan. `dependsOn` may reference only existing, \
                    live nodes - the plan stays acyclic by construction. The node starts PENDING. \
                    On an orchestration tick after its dependencies complete, the engine may \
                    reuse a Mate whose skillset label is exactly equal, or provision one and then \
                    dispatch the node. Skillsets are free-form scheduling labels; an unconfigured \
                    label uses the deployer's default Mate binding. Each plan \
                    mutation is validated atomically before it takes effect. Set `mateId` to an \
                    existing Mate id from `inspect_group` to assign that specific collaborator; \
                    it waits if that Mate is busy and never substitutes another Mate. Without it, \
                    skillset selects any matching available Mate. Set `newMate` to true when the \
                    task requires a different, independent collaborator; this creates a new member \
                    even if an existing Mate has the same skillset. Do not combine it with `mateId`. Direct dependency reports are \
                    included in the dispatched task; the Mate does not receive other agents' histories.
                    """,
            whenToUse =
                    """
                    Use `create_node` to build your plan node by node: one call per discrete \
                    task. Create dependencies before the nodes that need them - you author the \
                    plan from its foundations up. Also use it to extend the plan while the group \
                    is running or after its previous tasks have completed. When the user names a \
                    previous collaborator, resolve its actual Mate id from the earlier node and set `mateId`.
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
            security = "Only the group coordinator can change the task plan.",
            examples = {
                "{\"nodeId\": \"node-1\", \"description\": \"Implement JWT login in UserService\", \"skillset\": \"coding\"}",
                "{\"nodeId\": \"node-2\", \"description\": \"Test the login flow\", \"skillset\": \"testing\", \"dependsOn\": [\"node-1\"]}",
                "{\"nodeId\": \"node-2\", \"description\": \"Verify\", \"skillset\": \"testing\", \"dependsOn\": [\"node-9\"]}"
            },
            returnExamples = {
                "Node created: node-1 (skillset: coding). It is eligible for dispatch.",
                "Node created: node-2 (skillset: testing, depends on: node-1). It becomes eligible after its dependencies verify."
            })
    public static final class CreateNode implements GroupControlTool<CreateNode.Args> {

        private final @NonNull GroupControlCapability capability;

        public CreateNode(@NonNull GroupControlCapability capability) {
            this.capability = capability;
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
                        List<String> dependsOn,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Optional existing Mate id from inspect_group. Pins this task to that collaborator; omit for automatic assignment.")
                        String mateId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Set true to create a distinct collaborator for this task, even if another Mate is idle. Cannot be combined with mateId.")
                        Boolean newMate) {}

        @Override
        public @NonNull String getName() {
            return "create_node";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull GroupControlCapability groupControlCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull GroupControlCapability capability) {
            if (capability.snapshot() == null)
                return ToolErrors.failure(
                        "Node not created: no active group in your context. create_node is a Leader tool inside a group.");
            String nodeId = args.nodeId().strip();
            String description = args.description().strip();
            String skillset = args.skillset().strip();
            var requestedDependencies = args.dependsOn();
            Set<String> deps =
                    requestedDependencies == null
                            ? Set.of()
                            : new LinkedHashSet<>(requestedDependencies);
            NodeEdit edit =
                    capability.addNode(
                            nodeId,
                            description,
                            skillset,
                            deps,
                            args.mateId(),
                            Boolean.TRUE.equals(args.newMate()));
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
                    refuses running nodes so execution cannot disappear from the plan. Wait for \
                    their result before retiring them. Recorded assignments remain available.
                    """,
            whenToUse =
                    """
                    Use `remove_node` when re-planning makes a node obsolete - a strategic \
                    pivot, a task that turned out unnecessary, or a failed node you are replacing \
                    with a different approach.
                    """,
            whenNotToUse =
                    """
                    - Do not remove a COMPLETED node; completed work is checkpointed and stays.
                    - Do not remove a node others still depend on; re-plan or remove the \
                    dependents first (the error names them).
                    - Read a failed node's report before deciding how to replace it; failures are \
                    retained for the Leader to assess rather than automatically retried forever.
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
                    - Already stale or COMPLETED -> not removed; completed work remains checkpointed.
                    - RUNNING -> refused; removing a node is not cancellation.
                    """,
            security = "Only the group coordinator can remove task nodes.",
            examples = {"{\"nodeId\": \"node-2\"}", "{\"nodeId\": \"node-1\"}"},
            returnExamples = {"Node removed: node-2 (marked stale)."})
    public static final class RemoveNode implements GroupControlTool<RemoveNode.Args> {

        private final @NonNull GroupControlCapability capability;

        public RemoveNode(@NonNull GroupControlCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The id of the node to retire.")
                        @NonNull String nodeId) {}

        @Override
        public @NonNull String getName() {
            return "remove_node";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull GroupControlCapability groupControlCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull GroupControlCapability capability) {
            if (capability.snapshot() == null)
                return ToolErrors.failure(
                        "Node not removed: no active group in your context. remove_node is a Leader tool inside a group.");
            String nodeId = args.nodeId().strip();
            NodeEdit edit = capability.removeNode(nodeId);
            if (edit instanceof NodeEdit.Rejected r) {
                return ToolErrors.failure("Node not removed: " + r.reason());
            }
            return "Node removed: " + nodeId + " (marked stale).";
        }
    }
}
