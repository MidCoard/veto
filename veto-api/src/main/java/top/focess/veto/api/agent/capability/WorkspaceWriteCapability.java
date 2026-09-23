package top.focess.veto.api.agent.capability;

import java.io.IOException;
import org.jspecify.annotations.NonNull;

/** Resolves approved resources into restricted writable handles. */
public interface WorkspaceWriteCapability extends Capability {
    @NonNull WritableWorkspaceFile file(@NonNull String path) throws IOException;
}
