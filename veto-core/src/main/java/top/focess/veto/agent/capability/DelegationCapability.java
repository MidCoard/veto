package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.group.GroupTools.CreateGroup;

public sealed interface DelegationCapability extends Capability permits DelegationCapabilityImpl {
    @NonNull String createGroup(CreateGroup.@NonNull Args args);
}
