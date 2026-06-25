package top.focess.veto.agent.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VetoMdResolverTest {

    @Test
    void rootWithOnlyPrimaryVetoMd(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("r1");
        Files.createDirectories(root);
        Files.writeString(root.resolve("VETO.md"), "# Law A\n- rule A1");
        VetoMdResolver r =
                new VetoMdResolver(List.of(WorkspaceRoot.probe(root, TrustMarker.OWNED)));
        String law = r.resolve();
        assertTrue(law.contains("# Law A"));
        assertTrue(law.contains("- rule A1"));
    }

    @Test
    void overrideVetoMdWinsAndIsAppended(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("r1");
        Files.createDirectories(root.resolve(".veto"));
        Files.writeString(root.resolve("VETO.md"), "# Law A\n- rule A1");
        Files.writeString(root.resolve(".veto/VETO.md"), "# Override\n- rule O1");
        VetoMdResolver r =
                new VetoMdResolver(List.of(WorkspaceRoot.probe(root, TrustMarker.OWNED)));
        String law = r.resolve();
        // override appended after primary (later rules win)
        assertTrue(law.indexOf("# Law A") < law.indexOf("# Override"));
        assertTrue(law.contains("- rule O1"));
    }

    @Test
    void rootWithNeitherContributesNothing(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("r1");
        Files.createDirectories(root);
        VetoMdResolver r =
                new VetoMdResolver(List.of(WorkspaceRoot.probe(root, TrustMarker.OWNED)));
        assertEquals("", r.resolve());
    }

    @Test
    void crossRootConcatInOrder(@TempDir Path tmp) throws Exception {
        Path r1 = tmp.resolve("r1");
        Files.createDirectories(r1);
        Path r2 = tmp.resolve("r2");
        Files.createDirectories(r2);
        Files.writeString(r1.resolve("VETO.md"), "# Law R1");
        Files.writeString(r2.resolve("VETO.md"), "# Law R2");
        VetoMdResolver r =
                new VetoMdResolver(
                        List.of(
                                WorkspaceRoot.probe(r1, TrustMarker.OWNED),
                                WorkspaceRoot.probe(r2, TrustMarker.OWNED)));
        String law = r.resolve();
        assertTrue(law.indexOf("# Law R1") < law.indexOf("# Law R2"));
    }

    @Test
    void unreadableVetoMdIsSkipped(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("r1");
        Files.createDirectories(root);
        Path veto = root.resolve("VETO.md");
        Files.writeString(veto, "# Law");
        // make unreadable (best-effort — skipped test if the FS doesn't honor perms)
        boolean denied = veto.toFile().setReadable(false);
        try {
            VetoMdResolver r =
                    new VetoMdResolver(List.of(WorkspaceRoot.probe(root, TrustMarker.OWNED)));
            String law = r.resolve();
            if (denied) {
                assertEquals("", law, "unreadable VETO.md should be skipped, not throw");
            }
            // if the FS didn't deny (e.g. root on Windows), the file reads normally — not a failure
        } finally {
            veto.toFile().setReadable(true);
        }
    }
}
