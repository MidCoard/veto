package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code remove_mate} - remove an idle collaborator from the group. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Remove an idle collaborator from your group.\
                """,
        behavior =
                """
                Rejects unfinished assigned tasks. Requests member shutdown and confirms actual execution \
                exit before removing membership. Keeps task and Agent history.\
                """,
        whenToUse =
                """
                Use when the user no longer needs this collaborator in the team.\
                """,
        whenNotToUse =
                """
                Not a task cancellation tool. Finish or cancel assigned work first.\
                """,
        resultContract =
                """
                Success confirms member execution and dispatch waiter stopped. A timeout retains membership; \
                retry to confirm exit. Failure (failure, REQUEST_REJECTED): `Mate not removed: <reason>`.\
                """,
        errorsAndEdgeCases =
                """
                Unfinished task ids are listed. A stopping member cannot receive new work. Independent \
                background processes are not stopped.\
                """,
        security =
                """
                Caller must lead the current userId and Session scoped group.\
                """,
        examples = {
            "{\"mateId\":\"9b2e8c1a-4d5f-4e7b-8c9d-0a1b2c3d4e5f\"}",
            "{\"mateId\":\"5c1a2b3d-7e8f-4a5b-9c0d-1e2f3a4b5c6d\"}",
            "{\"mateId\":\"7d3e5f1a-2b4c-4d6e-8f0a-1b2c3d4e5f6a\"}",
            "{\"mateId\":\"00000000-0000-0000-0000-000000000000\"}"
        },
        returnExamples = {
            "Mate removed; execution exit confirmed. History retained. Independent background processes are not stopped.",
            "Mate removed; execution exit confirmed. History retained. Independent background processes are not stopped.",
            "Mate removed; execution exit confirmed. History retained. Independent background processes are not stopped.",
            "Mate not removed: Unknown Mate in this group: 00000000-0000-0000-0000-000000000000"
        })
public final class RemoveMateTool extends GroupControlTool<RemoveMateTool.Args> {
    private final GroupControlCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public RemoveMateTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public RemoveMateTool(@NonNull GroupControlCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code remove_mate}. */
    public record Args(@Doc("Existing collaborator id.") @NonNull String mateId) {}

    @Override
    public @NonNull String getName() {
        return "remove_mate";
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
        var result = capability.removeMate(args.mateId());
        if (result instanceof NodeEdit.Rejected rejected)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.REQUEST_REJECTED, "Mate not removed: " + rejected.reason());
        return "Mate removed; execution exit confirmed. History retained. Independent"
                + " background processes are not stopped.";
    }
}
