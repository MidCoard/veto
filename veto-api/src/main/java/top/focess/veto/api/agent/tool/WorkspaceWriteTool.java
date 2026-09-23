package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.WorkspaceWriteCapability;

/** A native tool whose filesystem operations use a call-scoped workspace-write capability. */
public interface WorkspaceWriteTool<T> extends NativeTool<T> {

    @NonNull String execute(@NonNull T args, @NonNull WorkspaceWriteCapability capability)
            throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        throw new SecurityException("Host must supply an authorized workspace capability");
    }
}
