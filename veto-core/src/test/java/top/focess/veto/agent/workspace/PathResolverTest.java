package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathResolverTest {

    private Workspace twoRoots(Path a, Path b) {
        return new Workspace(
                java.util.List.of(
                        WorkspaceRoot.probe(a, TrustMarker.OWNED),
                        WorkspaceRoot.probe(b, TrustMarker.OWNED)),
                PathMode.VIRTUAL,
                0);
    }

    @Test
    void virtualAbsoluteMapsToRootByDirName(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("auth-service");
        Files.createDirectories(a);
        Path b = tmp.resolve("auth-lib");
        Files.createDirectories(b);
        PathResolver r = twoRoots(a, b).pathResolver();
        // virtual prefix = /{rootDirName}; auth-service is root[0]
        Resolution res = r.resolveToHost("/auth-service/src/Main.java");
        assertTrue(res.inScope());
        assertEquals(0, res.rootIndex());
        assertEquals(a.resolve("src/Main.java"), res.hostPath());
    }

    @Test
    void virtualRelativeResolvesAgainstOperationalRoot(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("auth-service");
        Files.createDirectories(a);
        Path b = tmp.resolve("auth-lib");
        Files.createDirectories(b);
        PathResolver r = twoRoots(a, b).pathResolver();
        Resolution res = r.resolveToHost("src/Main.java");
        assertTrue(res.inScope());
        assertEquals(0, res.rootIndex());
        assertEquals(a.resolve("src/Main.java"), res.hostPath());
    }

    @Test
    void virtualUnknownRootIsOutOfScope(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("auth-service");
        Files.createDirectories(a);
        Path b = tmp.resolve("auth-lib");
        Files.createDirectories(b);
        PathResolver r = twoRoots(a, b).pathResolver();
        Resolution res = r.resolveToHost("/unknown-root/x");
        assertFalse(res.inScope());
        assertEquals(-1, res.rootIndex());
    }

    @Test
    void realModeFindsContainingRoot(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("auth-service");
        Files.createDirectories(a.resolve("src"));
        Path b = tmp.resolve("auth-lib");
        Files.createDirectories(b);
        Workspace ws =
                new Workspace(
                        java.util.List.of(
                                WorkspaceRoot.probe(a, TrustMarker.OWNED),
                                WorkspaceRoot.probe(b, TrustMarker.OWNED)),
                        PathMode.REAL,
                        0);
        PathResolver r = ws.pathResolver();
        Resolution res = r.resolveToHost(a.resolve("src/Main.java").toString());
        assertTrue(res.inScope());
        assertEquals(0, res.rootIndex());
    }

    @Test
    void realModeOutsideAllRootsIsOutOfScope(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("auth-service");
        Files.createDirectories(a);
        Path b = tmp.resolve("auth-lib");
        Files.createDirectories(b);
        Workspace ws =
                new Workspace(
                        java.util.List.of(
                                WorkspaceRoot.probe(a, TrustMarker.OWNED),
                                WorkspaceRoot.probe(b, TrustMarker.OWNED)),
                        PathMode.REAL,
                        0);
        PathResolver r = ws.pathResolver();
        Resolution res = r.resolveToHost(tmp.resolve("elsewhere/x").toString());
        assertFalse(res.inScope());
    }
}
