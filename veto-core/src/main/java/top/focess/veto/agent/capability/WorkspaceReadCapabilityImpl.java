package top.focess.veto.agent.capability;

import java.io.IOException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;

final class WorkspaceReadCapabilityImpl implements WorkspaceReadCapability {
    private final @NonNull ToolExecutionPermit permit;

    WorkspaceReadCapabilityImpl(@NonNull ToolExecutionPermit permit) {
        this.permit = permit;
    }

    @Override
    public @NonNull WorkspaceFile file(@NonNull String path) throws IOException {
        return new ReadOnlyWorkspaceFile(WorkspaceFileAccess.resolve(permit, path, false));
    }
}
