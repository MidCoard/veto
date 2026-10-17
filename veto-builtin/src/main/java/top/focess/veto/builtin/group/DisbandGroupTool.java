package top.focess.veto.builtin.group;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code disband_group} - tear down the active group and return the agent to STANDALONE. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Tear down your active group and return to single-agent autonomous mode.\
                """,
        behavior =
                """
                Ends your current group and stops its Mates, then returns you to the standalone role with the \
                standalone tools and model binding. The continuation includes the delegated outcome and any \
                available compaction summary. Group state is unavailable after a backend restart.\
                """,
        whenToUse =
                """
                Use `disband_group` only when the user explicitly requests that the group be disbanded or \
                asks to return to single-agent operation.\
                """,
        whenNotToUse =
                """
                - Prefer waiting for Mate status when practical; disbanding stops any remaining Mates. - Do \
                not disband merely because all DAG nodes are COMPLETED. Answer the user as Leader and retain \
                the group for follow-up work.\
                """,
        resultContract =
                """
                On success - empty; you continue in standalone operation with the outcome brief. Failure \
                (failure, NO_ACTIVE_GROUP): `Group not disbanded: no active group in your context. \
                disband_group is a Leader tool inside a group.`\
                """,
        errorsAndEdgeCases =
                """
                Mates still RUNNING -> the disband proceeds and their in-flight work may be lost. Calling \
                without an active group fails (failure, NO_ACTIVE_GROUP).\
                """,
        security =
                """
                Operate only on your current group; respect requests to stop ongoing work.\
                """,
        examples = {"{}"},
        returnExamples = {""})
public final class DisbandGroupTool extends GroupControlTool<DisbandGroupTool.Args> {

    private final GroupControlCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public DisbandGroupTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public DisbandGroupTool(@NonNull GroupControlCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code disband_group}; the tool takes no input. */
    public record Args() {}

    @Override
    public @NonNull String getName() {
        return "disband_group";
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
        GroupSnapshot group = capability.snapshot(getName());
        if (group == null) {
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Group not disbanded: no active group in your context. disband_group is "
                            + "a Leader tool inside a group.");
        }
        // Summarize the group's outcome for the reverse-transform brief (verified nodes' \
        // results), then tear the group down.
        String brief =
                capability.prompt(
                        "group-disband-brief", Map.of("group", GroupPromptInputs.snapshot(group)));
        capability.disband(brief);
        // Request the reverse transform: the runner rewinds, restores the STANDALONE persona +
        // binding, and re-injects the outcome brief so the agent continues autonomously.

        return "";
    }
}
