package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code create_mate} - create a named collaborator in the group. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Create a named collaborator in your group.\
                """,
        behavior =
                """
                Creates an idle Mate with an independent history. Responsibility describes its work, not \
                resource permissions. The returned Mate id identifies the same collaborator for future tasks.\
                """,
        whenToUse =
                """
                Create the people needed for your plan before assigning tasks.\
                """,
        whenNotToUse =
                """
                Do not create another member merely because an existing member is busy; tasks can queue.\
                """,
        resultContract =
                """
                Returns the created Mate id. Blank name or responsibility (failure, INVALID_ARGUMENTS): `Mate \
                not created: name and responsibility must not be blank.`. Failure leaves no assigned task.\
                """,
        errorsAndEdgeCases =
                """
                Blank names or responsibilities and unavailable groups are rejected.\
                """,
        security =
                """
                Caller must lead the current userId and Session scoped group.\
                """,
        examples = {
            "{\"name\":\"Alice\",\"responsibility\":\"Review the supplied calculations\"}",
            "{\"name\":\"Priya\",\"responsibility\":\"Implement and test the retry logic in the billing client\"}",
            "{\"name\":\"Kenji\",\"responsibility\":\"Investigate the memory regression and report findings with evidence\"}",
            "{\"name\":\"Morgan\",\"responsibility\":\"  \"}"
        },
        returnExamples = {
            "Mate created: 9b2e8c1a-4d5f-4e7b-8c9d-0a1b2c3d4e5f",
            "Mate created: 5c1a2b3d-7e8f-4a5b-9c0d-1e2f3a4b5c6d",
            "Mate created: 7d3e5f1a-2b4c-4d6e-8f0a-1b2c3d4e5f6a",
            "Mate not created: name and responsibility must not be blank."
        })
public final class CreateMateTool extends GroupControlTool<CreateMateTool.Args> {
    private final GroupControlCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public CreateMateTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public CreateMateTool(@NonNull GroupControlCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code create_mate}. */
    public record Args(
            @Doc("Display name, such as Alice.") @NonNull String name,
            @Doc("The collaborator responsibility.") @NonNull String responsibility) {}

    @Override
    public @NonNull String getName() {
        return "create_mate";
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
        if (args.name().isBlank() || args.responsibility().isBlank()) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Mate not created: name and responsibility must not be blank.");
        }
        return "Mate created: " + capability.createMate(args.name(), args.responsibility());
    }
}
