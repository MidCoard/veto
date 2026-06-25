package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProtectedSetTest {

    @Test
    void coversPathUnderEntry() {
        ProtectedSet ps =
                new ProtectedSet(Set.of(Path.of("/home/u/.ssh").toAbsolutePath().normalize()));
        assertTrue(ps.covers(Path.of("/home/u/.ssh/id_rsa").toAbsolutePath().normalize()));
        assertFalse(
                ps.covers(Path.of("/home/u/project/src/Main.java").toAbsolutePath().normalize()));
    }

    @Test
    void emptySetCoversNothing() {
        ProtectedSet ps = new ProtectedSet(Set.of());
        assertFalse(ps.covers(Path.of("/anywhere/x").toAbsolutePath().normalize()));
    }

    @Test
    void deployerDefaultsIncludeVetoAndSsh() {
        ProtectedSet ps = ProtectedSet.withDeployerDefaults();
        // ~/.veto and ~/.ssh entries should be present (absolute canonicalized)
        assertTrue(ps.paths().stream().anyMatch(p -> p.toString().contains(".veto")));
        assertTrue(ps.paths().stream().anyMatch(p -> p.toString().contains(".ssh")));
    }

    @Test
    void deployerDefaultsCoverWorkspaceEnvFiles() {
        // Spec §6: project .env files are among the protected defaults. They are
        // workspace-root-relative, so the roots must be threaded into withDeployerDefaults —
        // a project .env under PROTECT_SENSITIVE must be CRITICAL (covered), not DANGEROUS.
        Path root1 = Path.of("/home/u/proj1").toAbsolutePath().normalize();
        Path root2 = Path.of("/home/u/proj2").toAbsolutePath().normalize();
        ProtectedSet ps = ProtectedSet.withDeployerDefaults(List.of(root1, root2));
        assertTrue(ps.covers(root1.resolve(".env")));
        assertTrue(ps.covers(root2.resolve(".env")));
        // home-relative defaults are still present alongside the per-root .env entries
        assertTrue(ps.paths().stream().anyMatch(p -> p.toString().contains(".veto")));
    }

    @Test
    void fullPolicyHasNoProtectedSetEffect() {
        // FULL → no protected set; ProtectedSet.empty() covers nothing
        assertEquals(DeployerPolicy.FULL, DeployerPolicy.FULL);
        ProtectedSet empty = new ProtectedSet(Set.of());
        assertFalse(empty.covers(Path.of("/etc/shadow").toAbsolutePath().normalize()));
    }
}
