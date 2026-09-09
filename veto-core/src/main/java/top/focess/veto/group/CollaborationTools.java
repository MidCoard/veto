package top.focess.veto.group;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.GroupControlCapability;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.GroupControlTool;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolResultFormat;

public final class CollaborationTools {
    private CollaborationTools() {}

    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Create a named collaborator in your group.",
            behavior =
                    "Creates an idle Mate with an independent history. Responsibility describes its work, not resource permissions. The returned Mate id identifies the same collaborator for future tasks.",
            whenToUse = "Create the people needed for your plan before assigning tasks.",
            whenNotToUse =
                    "Do not create another member merely because an existing member is busy; tasks can queue.",
            resultContract = "Returns the created Mate id. Failure leaves no assigned task.",
            errorsAndEdgeCases =
                    "Blank names or responsibilities and unavailable groups are rejected.",
            security = "Caller must lead the current owner and Session scoped group.",
            examples =
                    "{\"name\":\"Alice\",\"responsibility\":\"Review the supplied calculations\"}",
            returnExamples = "Mate created: <mate-id>")
    public static final class CreateMate implements GroupControlTool<CreateMate.Args> {
        private final @NonNull GroupControlCapability capability;

        public CreateMate(@NonNull GroupControlCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @Doc("Display name, such as Alice.") @NonNull String name,
                @Doc("The collaborator responsibility.") @NonNull String responsibility) {}

        @Override
        public @NonNull String getName() {
            return "create_mate";
        }

        @Override
        @SuppressWarnings("nullness:return") // Java class literals cannot be null.
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull GroupControlCapability groupControlCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull GroupControlCapability capability) {
            return "Mate created: " + capability.createMate(args.name(), args.responsibility());
        }
    }

    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Assign one concrete task to an existing collaborator.",
            behavior =
                    "Registers work for the specified Mate. It starts after direct dependencies complete and the Mate is free; dependency reports are supplied automatically. Results are reported automatically to the Leader.",
            whenToUse =
                    "Use for work assigned to a collaborator, including follow-up work for the same person.",
            whenNotToUse =
                    "Do not use for background processes or create a task for your own final synthesis.",
            resultContract =
                    "Returns the registered task id and assignee. Registration is not completion.",
            errorsAndEdgeCases =
                    "Unknown members, duplicate ids and missing dependencies are rejected. A busy member causes queueing, not substitution.",
            security = "Caller must lead the current owner and Session scoped group.",
            examples =
                    "{\"taskId\":\"calculation\",\"description\":\"Calculate the supplied order total\",\"mateId\":\"<mate-id>\"}",
            returnExamples = "Task registered: calculation; assigned Mate: <mate-id>.")
    public static final class CreateTask implements GroupControlTool<CreateTask.Args> {
        private final @NonNull GroupControlCapability capability;

        public CreateTask(@NonNull GroupControlCapability capability) {
            this.capability = capability;
        }

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
        @SuppressWarnings("nullness:return") // Java class literals cannot be null.
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull GroupControlCapability groupControlCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull GroupControlCapability capability) {
            Set<String> deps =
                    args.dependsOn() == null ? Set.of() : new LinkedHashSet<>(args.dependsOn());
            var result =
                    capability.createTask(args.taskId(), args.description(), args.mateId(), deps);
            if (result instanceof GroupOrchestrator.NodeEdit.Rejected rejected)
                return ToolErrors.failure("Task not created: " + rejected.reason());
            return "Task registered: "
                    + args.taskId()
                    + "; assigned Mate: "
                    + args.mateId()
                    + ". It waits for its dependencies and this Mate to become available.";
        }
    }
}
