package top.focess.veto.agent.workspace;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;

/** One authorized filesystem root in a session workspace. */
public record WorkspaceRoot(@NonNull Path hostPath, @NonNull TrustMarker trust) {

    public WorkspaceRoot {
        hostPath = hostPath.toAbsolutePath().normalize();
    }

    public static @NonNull WorkspaceRoot of(@NonNull Path hostPath, @NonNull TrustMarker trust) {
        return new WorkspaceRoot(hostPath, trust);
    }
}
