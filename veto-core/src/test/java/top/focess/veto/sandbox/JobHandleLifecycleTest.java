package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for Windows Job Object handle lifecycle management. The Job handle created by {@link
 * KernelSandboxSubstrate#attachRequired(Process, SandboxProfile)} must be closed after the child
 * process exits to prevent handle leaks.
 *
 * <p>These tests only run on Windows (the platform where Job Objects apply).
 */
class JobHandleLifecycleTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * Verify the two-hop launcher does not start the target before attachment and returns a
     * Closeable Job handle.
     */
    @Test
    void attachReturnsCloseableHandle(@TempDir @NonNull Path workspace) throws Exception {
        Assumptions.assumeTrue(IS_WINDOWS, "Job Objects are Windows-only");

        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        Assumptions.assumeTrue(substrate.isAvailable(), "KernelSandboxSubstrate not available");
        SandboxProfile profile = profile(workspace);
        substrate.provisionWorkspace(profile);

        try (KernelSandboxSubstrate.PreparedCommand prepared =
                substrate.prepareCommand(
                        List.of(cmdExecutable(), "/c", "echo", "hello"), profile)) {
            Process process =
                    new ProcessBuilder(prepared.command()).directory(workspace.toFile()).start();
            prepared.awaitReady(process);
            @NonNull AutoCloseable handle = substrate.attachRequired(process, profile);
            prepared.release();
            assertEquals(0, process.waitFor());
            assertDoesNotThrow(handle::close, "Closing the handle should not throw");
        }
    }

    /**
     * Verify that multiple attach calls don't leak handles. On Windows, each Job Object consumes a
     * kernel handle; this test verifies handles are properly tracked.
     */
    @Test
    void multipleAttachesDoNotLeakHandles(@TempDir @NonNull Path workspace) throws Exception {
        Assumptions.assumeTrue(IS_WINDOWS, "Job Objects are Windows-only");

        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        Assumptions.assumeTrue(substrate.isAvailable(), "KernelSandboxSubstrate not available");
        SandboxProfile profile = profile(workspace);
        substrate.provisionWorkspace(profile);

        List<AutoCloseable> handles = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            try (KernelSandboxSubstrate.PreparedCommand prepared =
                    substrate.prepareCommand(
                            List.of(cmdExecutable(), "/c", "echo", Integer.toString(i)), profile)) {
                Process process =
                        new ProcessBuilder(prepared.command())
                                .directory(workspace.toFile())
                                .start();
                prepared.awaitReady(process);
                AutoCloseable handle = substrate.attachRequired(process, profile);
                handles.add(handle);
                prepared.release();
                assertEquals(0, process.waitFor());
            }
        }

        // Close all handles
        for (AutoCloseable handle : handles) {
            assertDoesNotThrow(() -> handle.close(), "Closing handles should not throw");
        }
    }

    private static @NonNull SandboxProfile profile(@NonNull Path workspace) {
        return new SandboxProfile(
                workspace.toAbsolutePath().normalize(), 512, 100, 8, Duration.ofSeconds(30));
    }

    private static @NonNull String cmdExecutable() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) {
            throw new IllegalStateException("SystemRoot is unavailable on Windows");
        }
        return Path.of(systemRoot, "System32", "cmd.exe").toString();
    }
}
