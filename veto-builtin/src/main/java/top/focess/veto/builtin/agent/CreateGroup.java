package top.focess.veto.builtin.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.DelegationCapability;
import top.focess.veto.api.agent.tool.*;

/** {@code create_group} - spawn a delegation. The calling agent transforms into the Leader. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description = "Request delegation for a task.",
        behavior =
                "Starts real collaboration using the supplied brief. Participants and work are arranged in the next stage.",
        whenToUse = "Use when the task meets the Delegation Rules in the system message.",
        whenNotToUse =
                "Unless the user explicitly requests collaborators, prefer direct execution for small or tightly coupled work.",
        resultContract =
                """
                    Success returns empty text. Failures:
                    - Blank brief (failure, INVALID_ARGUMENTS): `Group not created: blank brief. \
                    Pass a real description of the work.`
                    - No session (failure, NO_SESSION_CONTEXT): `Group not created: no authenticated \
                    session owner is available.`
                    """,
        errorsAndEdgeCases =
                """
                    A blank brief and a missing authenticated session owner are the only creation \
                    failures; both leave the group uncreated.
                    """,
        security = "Delegation remains within the user's authorized task and workspace boundaries.",
        examples = {
            "{\"task\": \"Review the persistence implementation and its callers, and verify the affected modules\"}",
            "{\"task\": \"Migrate the billing module from JPA to jOOQ; deliver the converted repositories and passing integration tests\"}",
            "{\"task\": \"Compare the three shortlisted message queues for the notification service and recommend one with a rationale\"}",
            "{\"task\": \"   \"}"
        },
        returnExamples = {
            "",
            "",
            "",
            "Group not created: blank brief. Pass a real description of the work."
        })
public final class CreateGroup implements DelegationTool<CreateGroup.Args> {

    private final DelegationCapability capability;

    public CreateGroup() {
        this.capability = null;
    }

    public CreateGroup(@NonNull DelegationCapability capability) {
        this.capability = capability;
    }

    public record Args(
            @SecurityHint(ParamCategory.GENERIC)
                    @Doc("Short brief of the work to be done and the expected result.")
                    @NonNull String task) {}

    @Override
    public @NonNull String getName() {
        return "create_group";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull DelegationCapability delegationCapability() {
        if (capability == null)
            throw new SecurityException("Host must supply an authorized delegation capability");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull DelegationCapability capability) {
        String task = args.task().strip();
        if (task.isBlank())
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Group not created: blank brief. Pass a real description of the work.");
        capability.createGroup(task);
        return "";
    }
}
