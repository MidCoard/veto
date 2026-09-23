package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.NetworkEgressCapability;

public interface NetworkEgressTool<T>
        extends NativeTool<T>, HostCapabilityTool<T, NetworkEgressCapability> {
    default @NonNull Class<NetworkEgressCapability> capabilityType() {
        return ToolDocs.nonNullClass(NetworkEgressCapability.class);
    }

    @NonNull NetworkEgressCapability networkEgressCapability();

    @NonNull String execute(@NonNull T args, @NonNull NetworkEgressCapability capability);

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, networkEgressCapability());
    }
}
