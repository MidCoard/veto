package top.focess.veto.api.agent.capability;

import java.io.IOException;
import org.jspecify.annotations.NonNull;

/** Resolves approved resources into restricted read handles. */
public interface WorkspaceReadCapability extends Capability {
    @NonNull String captureFileText(@NonNull String input);

    @NonNull WorkspaceFile file(@NonNull String path) throws IOException;
}
