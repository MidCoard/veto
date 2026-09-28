package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.NetworkEgressCapability;

/**
 * A native tool whose remote operations use a call-scoped network capability.
 *
 * @param <T> immutable argument value decoded by the host
 */
public abstract class NetworkEgressTool<T> extends NativeTool<T>
        implements HostCapabilityTool<T, NetworkEgressCapability> {
    /** Constructs a tool using call-scoped network access. */
    protected NetworkEgressTool() {}

    /**
     * @return the network capability interface required by this tool
     */
    public @NonNull Class<NetworkEgressCapability> capabilityType() {
        return NetworkEgressCapability.class;
    }

    /**
     * Retrieves the network port installed for this invocation.
     *
     * @return the network capability bound to the current admitted call
     */
    public abstract @NonNull NetworkEgressCapability networkEgressCapability();

    /**
     * Executes with the supplied authorized network capability.
     *
     * @param args decoded call arguments
     * @param capability capability authorized for this invocation
     * @return model-visible result content
     */
    public abstract @NonNull String execute(
            @NonNull T args, @NonNull NetworkEgressCapability capability);

    /**
     * Executes using {@link #networkEgressCapability()}.
     *
     * @param args decoded call arguments
     * @return model-visible result content
     */
    @Override
    public @NonNull String execute(@NonNull T args) {
        return execute(args, networkEgressCapability());
    }
}
