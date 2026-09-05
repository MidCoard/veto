package top.focess.veto.agent.capability;

import java.io.IOException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;

final class WorkspaceWriteCapabilityImpl implements WorkspaceWriteCapability {
    private final @NonNull ToolExecutionPermit permit;

    WorkspaceWriteCapabilityImpl(@NonNull ToolExecutionPermit permit) {
        this.permit = permit;
    }

    @Override
    public @NonNull WritableWorkspaceFile file(@NonNull String path) throws IOException {
        return new WritableWorkspaceFileImpl(WorkspaceFileAccess.resolve(permit, path, true));
    }
}
