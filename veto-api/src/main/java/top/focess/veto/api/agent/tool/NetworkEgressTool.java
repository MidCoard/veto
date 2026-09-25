package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.NetworkEgressCapability;

/**
 * A native tool whose remote operations use a call-scoped network capability.
 *
 * @param <T> immutable argument value decoded by the host
 */
public interface NetworkEgressTool<T>
        extends NativeTool<T>, HostCapabilityTool<T, NetworkEgressCapability> {
    /**
     * @return the network capability interface required by this tool
     */
    default @NonNull Class<NetworkEgressCapability> capabilityType() {
        return ToolDocs.nonNullClass(NetworkEgressCapability.class);
    }

    /**
     * Retrieves the network port installed for this invocation.
     *
     * @return the network capability bound to the current admitted call
     */
    @NonNull NetworkEgressCapability networkEgressCapability();

    /**
     * Executes with the supplied authorized network capability.
     *
     * @param args decoded call arguments
     * @param capability capability authorized for this invocation
     * @return model-visible result content
     */
    @NonNull String execute(@NonNull T args, @NonNull NetworkEgressCapability capability);

    /**
     * Executes using {@link #networkEgressCapability()}.
     *
     * @param args decoded call arguments
     * @return model-visible result content
     */
    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, networkEgressCapability());
    }
}
