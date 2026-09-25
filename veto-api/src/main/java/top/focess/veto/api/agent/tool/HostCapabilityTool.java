package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.Capability;

/**
 * A tool invoked with a host-authorized service port scoped to the current call.
 *
 * @param <T> decoded argument type
 * @param <C> host capability required by the implementation
 */
public interface HostCapabilityTool<T, C extends @NonNull Capability> extends CapabilityTool<T> {
    /**
     * Identifies the service port required for execution.
     *
     * @return the capability type the host must bind for an admitted invocation
     */
    @NonNull Class<C> capabilityType();

    /**
     * Executes using the call-scoped capability. Retaining the capability does not extend its
     * lifetime or admission after the invocation ends.
     *
     * @param args decoded call arguments
     * @param capability capability authorized for this invocation
     * @return model-visible result content
     * @throws Exception when execution cannot produce a successful result
     */
    @NonNull String execute(@NonNull T args, @NonNull C capability) throws Exception;
}
