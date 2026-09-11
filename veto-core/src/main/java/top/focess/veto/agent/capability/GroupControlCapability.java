package top.focess.veto.agent.capability;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.group.BlackboardMessage;
import top.focess.veto.group.BlackboardMessage.MessageType;
import top.focess.veto.group.Group.GroupState;
import top.focess.veto.group.GroupOrchestrator.Inspection;
import top.focess.veto.group.GroupOrchestrator.NodeEdit;
import top.focess.veto.group.GroupSnapshot;

public sealed interface GroupControlCapability extends Capability
        permits GroupControlCapabilityImpl {
    GroupSnapshot snapshot();

    @NonNull String createMate(@NonNull String name, @NonNull String responsibility);

    @NonNull NodeEdit createTask(
            @NonNull String id,
            @NonNull String description,
            @NonNull String mateId,
            @NonNull Set<String> dependencies);

    Inspection inspect(long since);

    @NonNull List<@NonNull BlackboardMessage> messages(long since);

    void awaitChange(long since, @NonNull GroupState state, int waitSeconds)
            throws InterruptedException;

    void disband(@NonNull String brief);

    void post(@NonNull String receiver, @NonNull MessageType type, @NonNull String payload);

    @NonNull NodeEdit addNode(
            @NonNull String id,
            @NonNull String description,
            @NonNull String skillset,
            @NonNull Set<String> dependencies,
            String mateId,
            boolean newMate);

    @NonNull NodeEdit removeNode(@NonNull String id);

    @NonNull NodeEdit cancelTask(@NonNull String id);

    @NonNull NodeEdit removeMate(@NonNull String id);
}
