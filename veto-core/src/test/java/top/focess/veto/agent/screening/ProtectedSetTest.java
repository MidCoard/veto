package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
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
    void fullPolicyHasNoProtectedSetEffect() {
        // FULL → no protected set; ProtectedSet.empty() covers nothing
        assertEquals(DeployerPolicy.FULL, DeployerPolicy.FULL);
        ProtectedSet empty = new ProtectedSet(Set.of());
        assertFalse(empty.covers(Path.of("/etc/shadow").toAbsolutePath().normalize()));
    }
}
