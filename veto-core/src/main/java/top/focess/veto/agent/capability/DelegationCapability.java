package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.Capability;

public sealed interface DelegationCapability extends Capability permits DelegationCapabilityImpl {
    void createGroup(@NonNull String task);
}
