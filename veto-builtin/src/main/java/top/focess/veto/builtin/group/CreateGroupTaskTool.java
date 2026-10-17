package top.focess.veto.builtin.group;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code create_task} - assign one concrete task to an existing collaborator. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Assign one concrete task to an existing collaborator.\
                """,
        behavior =
                """
                Registers work for the specified Mate. It starts after direct dependencies complete and the \
                Mate is free; dependency reports are supplied automatically. Results are reported \
                automatically to the Leader.\
                """,
        whenToUse =
                """
                Use for work assigned to a collaborator, including follow-up work for the same person.\
                """,
        whenNotToUse =
                """
                Do not use for background processes or create a task for your own final synthesis.\
                """,
        resultContract =
                """
                Returns the registered task id and assignee. Registration is not completion. Failure \
                (failure, REQUEST_REJECTED): `Task not created: <reason>`.\
                """,
        errorsAndEdgeCases =
                """
                Unknown members, duplicate ids and missing dependencies fail (failure, REQUEST_REJECTED). A \
                busy member causes queueing, not substitution.\
                """,
        security =
                """
                Caller must lead the current userId and Session scoped group.\
                """,
        examples = {
            "{\"taskId\":\"calculation\",\"description\":\"Calculate the supplied order total\",\"mateId\":\"9b2e8c1a-4d5f-4e7b-8c9d-0a1b2c3d4e5f\"}",
            "{\"taskId\":\"integration-tests\",\"description\":\"Run the integration suite against the migrated schema and report failures\",\"mateId\":\"5c1a2b3d-7e8f-4a5b-9c0d-1e2f3a4b5c6d\",\"dependsOn\":[\"schema-migration\"]}",
            "{\"taskId\":\"release-notes\",\"description\":\"Draft the release notes from the completed feature and fix reports\",\"mateId\":\"9b2e8c1a-4d5f-4e7b-8c9d-0a1b2c3d4e5f\",\"dependsOn\":[\"feature-api\",\"fix-retry\"]}",
            "{\"taskId\":\"retry-regression-test\",\"description\":\"Add a regression test for the race fixed in fix-retry\",\"mateId\":\"5c1a2b3d-7e8f-4a5b-9c0d-1e2f3a4b5c6d\",\"dependsOn\":[\"integration-tests\"]}",
            "{\"taskId\":\"docs\",\"description\":\"Write the API documentation\",\"mateId\":\"00000000-0000-0000-0000-000000000000\"}"
        },
        returnExamples = {
            "Task registered: calculation; assigned Mate: 9b2e8c1a-4d5f-4e7b-8c9d-0a1b2c3d4e5f. It waits for its dependencies and this Mate to become available.",
            "Task registered: integration-tests; assigned Mate: 5c1a2b3d-7e8f-4a5b-9c0d-1e2f3a4b5c6d. It waits for its dependencies and this Mate to become available.",
            "Task registered: release-notes; assigned Mate: 9b2e8c1a-4d5f-4e7b-8c9d-0a1b2c3d4e5f. It waits for its dependencies and this Mate to become available.",
            "Task registered: retry-regression-test; assigned Mate: 5c1a2b3d-7e8f-4a5b-9c0d-1e2f3a4b5c6d. It waits for its dependencies and this Mate to become available.",
            "Task not created: Unknown Mate in this group: 00000000-0000-0000-0000-000000000000"
        })
public final class CreateGroupTaskTool extends GroupControlTool<CreateGroupTaskTool.Args> {
    private final GroupControlCapability capability;
    private final @NonNull Runnable await;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public CreateGroupTaskTool() {
        this(null, () -> {});
    }

    /** Creates an instance bound to the given host capability. */
    public CreateGroupTaskTool(@NonNull GroupControlCapability capability) {
        this(capability, () -> {});
    }

    /** Creates an instance that runs {@code await} after each successful registration. */
    public CreateGroupTaskTool(GroupControlCapability capability, @NonNull Runnable await) {
        this.capability = capability;
        this.await = await;
    }

    /** Model-facing arguments of {@code create_task}. */
    public record Args(
            @Doc("Unique task id within the group.") @NonNull String taskId,
            @Doc("Concrete instructions and expected result.") @NonNull String description,
            @Doc("Existing collaborator id.") @NonNull String mateId,
            @Doc("Existing task ids whose reports are needed.") List<String> dependsOn) {}

    @Override
    public @NonNull String getName() {
        return "create_task";
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
        Set<String> deps =
                args.dependsOn() == null ? Set.of() : new LinkedHashSet<>(args.dependsOn());
        var group = capability.snapshot(getName());
        if (group == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Task not created: no active group in your context. create_task is a Leader"
                            + " tool inside a group.");
        String responsibility = group.mates().get(args.mateId());
        if (responsibility == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.REQUEST_REJECTED,
                    "Task not created: Unknown Mate in this group: " + args.mateId());
        var result =
                capability.createTask(
                        args.taskId(), args.description(), args.mateId(), responsibility, deps);
        if (result instanceof NodeEdit.Rejected rejected)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.REQUEST_REJECTED, "Task not created: " + rejected.reason());
        await.run();
        return "Task registered: "
                + args.taskId()
                + "; assigned Mate: "
                + args.mateId()
                + ". It waits for its dependencies and this Mate to become available.";
    }
}
