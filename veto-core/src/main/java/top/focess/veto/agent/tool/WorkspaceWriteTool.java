package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;

/** A native tool restricted to the Gateway-issued workspace-write capability. */
public interface WorkspaceWriteTool<T> extends CapabilityTool<T, WorkspaceWriteCapability> {

    @Override
    default @NonNull Class<WorkspaceWriteCapability> getCapabilityClass() {
        return ToolDocs.nonNullClass(WorkspaceWriteCapability.class);
    }
}
