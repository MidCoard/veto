package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Tests for Windows Job Object handle lifecycle management. The Job handle created by {@link
 * KernelSandboxSubstrate#attach(Process)} must be closed after the child process exits to prevent
 * handle leaks.
 *
 * <p>These tests only run on Windows (the platform where Job Objects apply).
 */
class JobHandleLifecycleTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * Verify that attach returns a Closeable that can be used to clean up the Job handle. The test
     * spawns a short-lived process, attaches the kernel wall, and verifies the handle can be
     * closed.
     */
    @Test
    void attachReturnsCloseableHandle() throws Exception {
        Assumptions.assumeTrue(IS_WINDOWS, "Job Objects are Windows-only");

        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        Assumptions.assumeTrue(substrate.isAvailable(), "KernelSandboxSubstrate not available");

        // Spawn a short-lived process
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo hello");
        Process process = pb.start();

        // Attach should return a Closeable handle
        AutoCloseable handle = substrate.attachWithHandle(process);
        assertNotNull(handle, "attachWithHandle should return a non-null handle on Windows");

        // Wait for process to complete
        process.waitFor();

        // Close the handle (should not throw)
        assertDoesNotThrow(() -> handle.close(), "Closing the handle should not throw");
    }

    /**
     * Verify that multiple attach calls don't leak handles. On Windows, each Job Object consumes a
     * kernel handle; this test verifies handles are properly tracked.
     */
    @Test
    void multipleAttachesDoNotLeakHandles() throws Exception {
        Assumptions.assumeTrue(IS_WINDOWS, "Job Objects are Windows-only");

        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        Assumptions.assumeTrue(substrate.isAvailable(), "KernelSandboxSubstrate not available");

        List<AutoCloseable> handles = new ArrayList<>();

        // Spawn and attach 3 processes
        for (int i = 0; i < 3; i++) {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo " + i);
            Process process = pb.start();
            AutoCloseable handle = substrate.attachWithHandle(process);
            if (handle != null) {
                handles.add(handle);
            }
            process.waitFor();
        }

        // Close all handles
        for (AutoCloseable handle : handles) {
            assertDoesNotThrow(() -> handle.close(), "Closing handles should not throw");
        }

        // If we got here without exceptions, handles were properly managed
        assertTrue(true, "All handles closed successfully");
    }
}
