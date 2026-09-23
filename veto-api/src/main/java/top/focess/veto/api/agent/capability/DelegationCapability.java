package top.focess.veto.api.agent.capability;

import org.jspecify.annotations.NonNull;

public interface DelegationCapability extends Capability {
    void createGroup(@NonNull String task);
}
