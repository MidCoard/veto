package top.focess.veto.group;

import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolSecurity;

/**
 * The agent-facing group management tools (delegation_spawning.md). The Leader uses these to create
 * / disband groups, add / remove Mates, and dispatch tasks. Mates use {@code postMessage} to send
 * messages to the Leader (blackboard.md §3.2).
 *
 * <p>These are native tools — they pass through the Gateway (read of own data is SAFE, writes are
 * ELEVATED + audited).
 */
public final class GroupTools {

    private GroupTools() {}

    /** {@code create_group} — spawn a delegation. The Leader calls this on the top-tier model. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class CreateGroup implements NativeMcpTool<CreateGroup.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Short brief of the work to be done (seeds the Leader's investigation).")
                        String task,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional JSON-encoded DAG (will be parsed on dispatch).")
                        String dag) {}

        @Override
        public String getName() {
            return "create_group";
        }

        @Override
        public String getDescription() {
            return "Spawn a delegation (a group of agents) to accomplish a goal. "
                    + "The Leader authors the DAG from the contextBrief; Mates are created via create_mate.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            // Wired at runtime by McpEngineImpl; the body is invoked through the MCP engine.
            return "";
        }
    }

    /** {@code disband_group} — tear down an active group. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class DisbandGroup implements NativeMcpTool<DisbandGroup.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The group id to disband.")
                        String groupId) {}

        @Override
        public String getName() {
            return "disband_group";
        }

        @Override
        public String getDescription() {
            return "Tear down an active group. The Blackboard is retained for audit.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }

    /** {@code create_mate} — add a Mate to a group. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class CreateMate implements NativeMcpTool<CreateMate.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("New Mate's id (unique within the group).")
                        String mateId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Skillset the Mate specializes in (e.g. 'coding', 'testing').")
                        String skillset) {}

        @Override
        public String getName() {
            return "create_mate";
        }

        @Override
        public String getDescription() {
            return "Add a Mate to an active group. The Mate joins under a skillset tag.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }

    /** {@code remove_mate} — remove a Mate from a group. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class RemoveMate implements NativeMcpTool<RemoveMate.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Mate id to remove.") String mateId) {}

        @Override
        public String getName() {
            return "remove_mate";
        }

        @Override
        public String getDescription() {
            return "Remove a Mate from an active group. In-flight nodes go to PENDING for re-assignment.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }

    /** {@code dispatchTask} — Leader → Mate: a task or revision instruction. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class DispatchTask implements NativeMcpTool<DispatchTask.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Target Mate id.") String mateId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "The Leader's authored instruction (the Leader reasons over any "
                                        + "incoming messages and composes this itself; no pass-through).")
                        String instruction) {}

        @Override
        public String getName() {
            return "dispatchTask";
        }

        @Override
        public String getDescription() {
            return "Push a Leader-authored task or revision instruction to a Mate. "
                    + "Posted to the Blackboard as TASK_DISPATCH.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }

    /** {@code postMessage} — Mate → Leader: ARTIFACT_REF / LOG_REF / FEEDBACK / STATUS / ACCEPT. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class PostMessage implements NativeMcpTool<PostMessage.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Message type: ARTIFACT_REF, LOG_REF, FEEDBACK, STATUS, ACCEPT.")
                        String type,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Small payload (path / status / short feedback / node-task instruction). "
                                        + "Mates must NOT post file contents — paths only.")
                        String payload) {}

        @Override
        public String getName() {
            return "postMessage";
        }

        @Override
        public String getDescription() {
            return "Mate → Leader message (ARTIFACT_REF / LOG_REF / FEEDBACK / STATUS / ACCEPT). "
                    + "Receiver is always the Leader (hub-and-spoke).";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }

    /** {@code postStatus} — Leader/Mate: update a DAG node's status (PENDING/ASSIGNED/...). */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class PostStatus implements NativeMcpTool<PostStatus.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC) @Doc("DAG node id.") String nodeId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "New status: PENDING / ASSIGNED / RUNNING / VERIFIED / FAILED / STALE.")
                        String status) {}

        @Override
        public String getName() {
            return "postStatus";
        }

        @Override
        public String getDescription() {
            return "Update a DAG node's status. Persists in the group record.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }
}
