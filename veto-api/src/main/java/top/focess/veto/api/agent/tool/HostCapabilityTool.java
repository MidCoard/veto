package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.Capability;

/** A tool implementation invoked with a host-authorized service port. */
public interface HostCapabilityTool<T, C extends @NonNull Capability> extends CapabilityTool<T> {
    @NonNull Class<C> capabilityType();

    @NonNull String execute(@NonNull T args, @NonNull C capability) throws Exception;
}
