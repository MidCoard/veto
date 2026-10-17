package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code remove_node} — retire a node from the group's plan (marked STALE). */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Retire a node from your group's plan - re-planning marks it stale rather than deleting it.\
                """,
        behavior =
                """
                Marks the node STALE and keeps it in the plan record for audit. New nodes cannot depend on \
                it, and live dependents must be removed or re-planned first. This call refuses running nodes \
                so execution cannot disappear from the plan. Wait for their result before retiring them. \
                Recorded assignments remain available.\
                """,
        whenToUse =
                """
                Use `remove_node` when re-planning makes a node obsolete - a strategic pivot, a task that \
                turned out unnecessary, or a failed node you are replacing with a different approach.\
                """,
        whenNotToUse =
                """
                - Do not remove a COMPLETED node; completed work is checkpointed and stays. - Do not remove a \
                node others still depend on; re-plan or remove the dependents first (the error names them). - \
                Read a failed node's report before deciding how to replace it; failures are retained for the \
                Leader to assess rather than automatically retried forever.\
                """,
        resultContract =
                """
                On success: Node removed: node-2 (marked stale). Failures, all `Node not removed: <reason and \
                what to do next>`: no active group (failure, NO_ACTIVE_GROUP); removal refused (failure, \
                REQUEST_REJECTED).\
                """,
        errorsAndEdgeCases =
                """
                - Unknown `nodeId` (failure, REQUEST_REJECTED): `Node not removed: node not found: <id>`. - \
                Live dependents exist -> refused, naming the dependents. - Already stale or COMPLETED -> not \
                removed; completed work remains checkpointed. - RUNNING -> refused; removing a node is not \
                cancellation.\
                """,
        security =
                """
                Only the group coordinator can remove task nodes.\
                """,
        examples = {
            "{\"nodeId\": \"node-2\"}",
            "{\"nodeId\": \"node-4\"}",
            "{\"nodeId\": \"node-99\"}"
        },
        returnExamples = {
            "Node removed: node-2 (marked stale).",
            "Node removed: node-4 (marked stale).",
            "Node not removed: node not found: node-99"
        })
public final class RemoveGroupNodeTool extends GroupControlTool<RemoveGroupNodeTool.Args> {

    private final GroupControlCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public RemoveGroupNodeTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public RemoveGroupNodeTool(@NonNull GroupControlCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code remove_node}. */
    public record Args(
            @SecurityHint(ParamCategory.GENERIC) @Doc("The id of the node to retire.")
                    @NonNull String nodeId) {}

    @Override
    public @NonNull String getName() {
        return "remove_node";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull GroupControlCapability groupControlCapability() {
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull GroupControlCapability capability) {
        if (capability.snapshot(getName()) == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Node not removed: no active group in your context. remove_node is a Leader"
                            + " tool inside a group.");
        String nodeId = args.nodeId().strip();
        NodeEdit edit = capability.removeNode(nodeId);
        if (edit instanceof NodeEdit.Rejected r) {
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.REQUEST_REJECTED, "Node not removed: " + r.reason());
        }
        return "Node removed: " + nodeId + " (marked stale).";
    }
}
