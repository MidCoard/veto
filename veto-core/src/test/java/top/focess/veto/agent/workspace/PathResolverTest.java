package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
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

    @Test
    void virtualAbsoluteTraversalEscapeIsOutOfScope(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("auth-service");
        Files.createDirectories(a);
        Path b = tmp.resolve("auth-lib");
        Files.createDirectories(b);
        PathResolver r = twoRoots(a, b).pathResolver();
        // /{rootDirName}/../../elsewhere/x normalizes to a host OUTSIDE the matched root → escape.
        Resolution res = r.resolveToHost("/auth-service/../../elsewhere/x");
        assertFalse(res.inScope());
        assertEquals(-1, res.rootIndex());
    }

    @Test
    void virtualRelativeTraversalEscapeIsOutOfScope(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("auth-service");
        Files.createDirectories(a);
        Path b = tmp.resolve("auth-lib");
        Files.createDirectories(b);
        PathResolver r = twoRoots(a, b).pathResolver();
        // relative ../../elsewhere resolves against the operational root and escapes it.
        Resolution res = r.resolveToHost("../../elsewhere/x");
        assertFalse(res.inScope());
        assertEquals(-1, res.rootIndex());
    }

    @Test
    void virtualSymlinkEscapeIsOutOfScope(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("auth-service");
        Files.createDirectories(a);
        Path b = tmp.resolve("auth-lib");
        Files.createDirectories(b);
        // A symlink inside root a pointing to a directory OUTSIDE all roots. toRealPath() follows
        // it to the real target, which is then classified against the roots → out-of-scope.
        Path outside = tmp.resolve("outside-target");
        Files.createDirectories(outside);
        Files.createFile(outside.resolve("real-file.txt"));
        Path link = a.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.assumeTrue(
                    false, "symlinks not supported on this filesystem: " + e.getMessage());
            return;
        }
        PathResolver r = twoRoots(a, b).pathResolver();
        Resolution res = r.resolveToHost("/auth-service/escape-link/real-file.txt");
        assertFalse(res.inScope());
        assertEquals(-1, res.rootIndex());
    }
}
