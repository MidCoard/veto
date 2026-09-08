package top.focess.veto.agent.capability;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.group.BlackboardMessage;
import top.focess.veto.group.BlackboardMessage.MessageType;
import top.focess.veto.group.Group.GroupState;
import top.focess.veto.group.GroupOrchestrator.NodeEdit;
import top.focess.veto.group.GroupSnapshot;

public sealed interface GroupControlCapability extends Capability
        permits GroupControlCapabilityImpl {
    GroupSnapshot snapshot();

    @NonNull List<@NonNull BlackboardMessage> messages(long since);

    void awaitChange(long since, @NonNull GroupState state, int waitSeconds)
            throws InterruptedException;

    void disband(@NonNull String brief);

    void post(@NonNull String receiver, @NonNull MessageType type, @NonNull String payload);

    @NonNull NodeEdit addNode(
            @NonNull String id,
            @NonNull String description,
            @NonNull String skillset,
            @NonNull Set<String> dependencies);

    @NonNull NodeEdit removeNode(@NonNull String id);
}
