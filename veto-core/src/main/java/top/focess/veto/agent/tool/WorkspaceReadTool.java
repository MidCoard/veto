package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.WorkspaceReadCapability;

/** A native tool restricted to the Gateway-issued workspace-read capability. */
public interface WorkspaceReadTool<T> extends CapabilityTool<T, WorkspaceReadCapability> {

    @Override
    default @NonNull Class<WorkspaceReadCapability> getCapabilityClass() {
        return ToolDocs.nonNullClass(WorkspaceReadCapability.class);
    }
}
