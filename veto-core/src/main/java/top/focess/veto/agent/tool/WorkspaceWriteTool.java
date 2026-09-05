package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.CapabilityResolver;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;

/** A native tool whose filesystem operations use a call-scoped workspace-write capability. */
public non-sealed interface WorkspaceWriteTool<T> extends NativeTool<T> {

    @NonNull String execute(@NonNull T args, @NonNull WorkspaceWriteCapability capability)
            throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        return execute(
                args,
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class)));
    }
}
