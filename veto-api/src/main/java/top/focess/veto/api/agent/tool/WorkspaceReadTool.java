package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.WorkspaceReadCapability;

/** A native tool whose filesystem operations use a call-scoped workspace-read capability. */
public interface WorkspaceReadTool<T> extends NativeTool<T> {

    @NonNull String execute(@NonNull T args, @NonNull WorkspaceReadCapability capability)
            throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        throw new SecurityException("Host must supply an authorized workspace capability");
    }
}
