package top.focess.veto.agent.capability;

import java.io.IOException;
import org.jspecify.annotations.NonNull;

/** Resolves approved resources into restricted writable handles. */
public sealed interface WorkspaceWriteCapability extends Capability
        permits WorkspaceWriteCapabilityImpl {
    @NonNull WritableWorkspaceFile file(@NonNull String path) throws IOException;
}
