package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class MacOsSeatbeltSandboxTest {

    @Test
    void profileIsDefaultDenyAndOnlyMakesWorkspaceWritable() {
        Path workspace = Path.of("workspace with space");

        String profile = MacOsSeatbeltSandbox.profile(workspace);

        assertTrue(profile.startsWith("(version 1)\n(deny default)"));
        assertTrue(profile.contains("workspace with space"));
        assertTrue(profile.contains("(allow file-write* (subpath"));
        assertTrue(profile.contains("(deny file-write* (subpath"));
        assertTrue(profile.contains(".agents"));
        assertFalse(profile.contains(".git"));
        assertFalse(profile.contains("(allow network"));
        assertFalse(profile.contains("(allow file-read*)"));
        assertFalse(profile.contains("(allow sysctl-read)"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macOsBackendRunsInsideWorkspace(@TempDir @NonNull Path workspace) {
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxHandle handle = substrate.provision(profile(workspace));

        CommandResult result =
                substrate.runCommands(
                        handle,
                        List.of(new Command("/usr/bin/printf", List.of("seatbelt-ok"))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(10));

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("seatbelt-ok", result.stdout());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macOsBackendDeniesReadOutsideWorkspace(@TempDir @NonNull Path workspace) throws Exception {
        Path outside = Files.createTempFile("veto-seatbelt-secret", ".txt");
        Files.writeString(outside, "must-not-be-readable");
        try {
            KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
            ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
            SandboxHandle handle = substrate.provision(profile(workspace));

            CommandResult result =
                    substrate.runCommands(
                            handle,
                            List.of(new Command("/bin/cat", List.of(outside.toString()))),
                            Path.of("."),
                            ChainMode.STOP_ON_FAILURE,
                            Duration.ofSeconds(10));

            assertNotEquals(0, result.exitCode());
            assertFalse(result.stdout().contains("must-not-be-readable"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private static int occurrences(@NonNull String value, @NonNull String needle) {
        return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static @NonNull SandboxProfile profile(@NonNull Path workspace) {
        return new SandboxProfile(workspace, 512, 100, 16, Duration.ofSeconds(30));
    }
}
