package top.focess.veto.api.agent.capability;

import java.io.IOException;
import org.jspecify.annotations.NonNull;

/** Resolves approved resources into restricted read handles. */
public interface WorkspaceReadCapability extends Capability {
    /**
     * Captures approved textual input as a workspace resource.
     *
     * @param input text supplied by the admitted call
     * @return the host-issued resource reference
     */
    @NonNull String captureFileText(@NonNull String input);

    /**
     * Resolves an approved path to a restricted read handle.
     *
     * @param path workspace-relative path authorized for this invocation
     * @return the restricted file handle
     * @throws IOException when the path cannot be resolved safely
     */
    @NonNull WorkspaceFile file(@NonNull String path) throws IOException;
}
