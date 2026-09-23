package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.NetworkEgressCapability;
import top.focess.veto.api.agent.tool.NativeTool;

public interface NetworkEgressTool<T> extends NativeTool<T> {
    @NonNull NetworkEgressCapability networkEgressCapability();

    @NonNull String execute(@NonNull T args, @NonNull NetworkEgressCapability capability);

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, networkEgressCapability());
    }
}
