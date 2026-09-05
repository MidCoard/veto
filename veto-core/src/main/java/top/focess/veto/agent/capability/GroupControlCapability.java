package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.group.DagTools.CreateNode;
import top.focess.veto.group.DagTools.RemoveNode;
import top.focess.veto.group.GroupTools.DisbandGroup;
import top.focess.veto.group.GroupTools.InspectGroup;
import top.focess.veto.group.GroupTools.PostMessage;

public sealed interface GroupControlCapability extends Capability
        permits GroupControlCapabilityImpl {
    @NonNull String disband(DisbandGroup.@NonNull Args args);

    @NonNull String inspect(InspectGroup.@NonNull Args args);

    @NonNull String post(PostMessage.@NonNull Args args);

    @NonNull String createNode(CreateNode.@NonNull Args args);

    @NonNull String removeNode(RemoveNode.@NonNull Args args);
}
