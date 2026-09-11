package top.focess.veto.agent.capability;

import java.io.IOException;
import org.jspecify.annotations.NonNull;

/** Resolves approved resources into restricted read handles. */
public sealed interface WorkspaceReadCapability extends Capability
        permits WorkspaceReadCapabilityImpl, ProtectedWorkspaceReadCapabilityImpl {
    @NonNull String captureFileText(@NonNull String input);

    @NonNull WorkspaceFile file(@NonNull String path) throws IOException;
}
