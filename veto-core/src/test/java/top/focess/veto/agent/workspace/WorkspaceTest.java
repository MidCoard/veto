package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @Test
    void rootsAreOrderedAndCurrentRootDefaultsToZero(
            @TempDir @org.jspecify.annotations.NonNull Path tmp) throws Exception {
        Path a = tmp.resolve("a");
        Files.createDirectories(a);
        Path b = tmp.resolve("b");
        Files.createDirectories(b);
        Workspace ws =
                new Workspace(
                        List.of(
                                WorkspaceRoot.of(a, TrustMarker.OWNED),
                                WorkspaceRoot.of(b, TrustMarker.OWNED)),
                        PathMode.VIRTUAL,
                        0);
        assertEquals(2, ws.roots().size());
        assertEquals(a, ws.roots().get(0).hostPath());
        assertEquals(0, ws.currentRootIndex());
    }

    @Test
    void emptyRootsRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> new Workspace(List.of(), PathMode.REAL, 0));
    }

    @Test
    void currentRootIndexOutOfRangeRejected(@TempDir @org.jspecify.annotations.NonNull Path tmp)
            throws Exception {
        Path a = tmp.resolve("a");
        Files.createDirectories(a);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Workspace(
                                List.of(WorkspaceRoot.of(a, TrustMarker.OWNED)), PathMode.REAL, 5));
    }

    @Test
    void singleRootFactoryRoundTrips(@TempDir @org.jspecify.annotations.NonNull Path tmp)
            throws Exception {
        Path a = tmp.resolve("a");
        Files.createDirectories(a);
        Workspace ws = Workspace.single(a, PathMode.REAL);
        assertEquals(1, ws.roots().size());
        assertEquals(PathMode.REAL, ws.pathMode());
    }

    @Test
    void configSelectsAnExplicitCurrentRoot(@TempDir @org.jspecify.annotations.NonNull Path tmp)
            throws Exception {
        Path first = Files.createDirectories(tmp.resolve("first"));
        Path selected = Files.createDirectories(tmp.resolve("selected"));
        Workspace ws = Workspace.fromConfig("", first + "," + selected, PathMode.REAL.name(), 1);

        assertEquals(1, ws.currentRootIndex());
        assertEquals(selected.toAbsolutePath().normalize(), ws.currentHostRoot());
    }

    @Test
    void exposesResolvers(@TempDir @org.jspecify.annotations.NonNull Path tmp) throws Exception {
        Path a = tmp.resolve("a");
        Files.createDirectories(a);
        Workspace ws = Workspace.single(a, PathMode.VIRTUAL);
        assertNotNull(ws.pathResolver());
        assertNotNull(ws.vetoMdResolver());
    }
}
