package top.focess.veto.api.agent.capability;

import java.io.IOException;
import org.jspecify.annotations.NonNull;

/** Resolves approved resources into restricted writable handles. */
public interface WorkspaceWriteCapability extends Capability {
    /**
     * Resolves an approved path to a restricted writable handle.
     *
     * @param path workspace-relative path authorized for this invocation
     * @return the restricted writable handle
     * @throws IOException when the path cannot be resolved safely
     */
    @NonNull WritableWorkspaceFile file(@NonNull String path) throws IOException;
}
