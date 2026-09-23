package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.CapabilityResolver;
import top.focess.veto.agent.capability.WorkspaceReadCapability;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolDocs;

/** A native tool whose filesystem operations use a call-scoped workspace-read capability. */
public interface WorkspaceReadTool<T> extends NativeTool<T> {

    @NonNull String execute(@NonNull T args, @NonNull WorkspaceReadCapability capability)
            throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        return execute(
                args,
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceReadCapability.class)));
    }
}
