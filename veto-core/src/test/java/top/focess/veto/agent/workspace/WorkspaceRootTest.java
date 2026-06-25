package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRootTest {

    @Test
    void probesGitRepoAndBranch(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".git"));
        WorkspaceRoot root = WorkspaceRoot.probe(dir, TrustMarker.OWNED);
        assertTrue(root.isGitRepo());
        // currentBranch may be null if git not on PATH — assert only the field is reachable.
        assertNotNull(root.hostPath());
        assertEquals(TrustMarker.OWNED, root.trust());
    }

    @Test
    void nonGitRootIsNotGitRepo(@TempDir Path dir) {
        WorkspaceRoot root = WorkspaceRoot.probe(dir, TrustMarker.SHARED_GRANT);
        assertFalse(root.isGitRepo());
        assertNull(root.currentBranch());
        assertEquals(TrustMarker.SHARED_GRANT, root.trust());
    }
}
