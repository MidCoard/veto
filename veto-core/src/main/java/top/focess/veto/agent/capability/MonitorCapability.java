package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;

public sealed interface MonitorCapability extends Capability permits MonitorCapabilityImpl {
    @NonNull String create(@NonNull String purpose, Long afterSeconds, String at);

    @NonNull String inspect();

    @NonNull String control(@NonNull String id, @NonNull String operation);
}
