package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRootTest {

    @Test
    void normalizesTheAuthorizedPath(@TempDir @org.jspecify.annotations.NonNull Path dir) {
        WorkspaceRoot root = WorkspaceRoot.of(dir.resolve("child/.."), TrustMarker.OWNED);
        assertEquals(dir.toAbsolutePath().normalize(), root.hostPath());
        assertEquals(TrustMarker.OWNED, root.trust());
    }

    @Test
    void retainsItsAccessMarker(@TempDir @org.jspecify.annotations.NonNull Path dir) {
        WorkspaceRoot root = WorkspaceRoot.of(dir, TrustMarker.SHARED_GRANT);
        assertEquals(TrustMarker.SHARED_GRANT, root.trust());
    }
}
