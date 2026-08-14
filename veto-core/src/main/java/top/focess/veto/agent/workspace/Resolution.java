package top.focess.veto.agent.workspace;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;

/** The result of resolving an agent path: which root it landed in, or out-of-scope. */
public record Resolution(Path hostPath, int rootIndex, boolean inScope) {
    public static @NonNull Resolution outOfScope(Path hostPath) {
        return new Resolution(hostPath, -1, false);
    }
}
