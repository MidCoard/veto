package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;

public sealed interface DelegationCapability extends Capability permits DelegationCapabilityImpl {
    void createGroup(@NonNull String task);
}
