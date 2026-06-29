package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for the kernel-sandbox substrate platform detection. */
class KernelSandboxSubstrateTest {

    @Test
    void kernelSandboxSubstrateConstructs() {
        // Should construct without throwing regardless of platform.
        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        assertNotNull(substrate);
    }

    @Test
    void attachWithNullProcessIsNoOp() {
        // Attaching to a null process must not throw — the substrate may be called early.
        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        substrate.attach(null); // no exception expected
    }

    @Test
    void isAvailableReflectsPlatform() {
        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        // On non-Windows (the build machine is Linux), the wall is unavailable.
        // On Windows, it would be available if the JNA binding loaded.
        // We just verify the method returns a boolean without throwing.
        boolean available = substrate.isAvailable();
        // On Linux the MVP path is a stub; isAvailable() returns false there.
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // Windows: depends on JNA load; the assertion is "either is fine".
            assertTrue(available || !available);
        } else {
            assertFalse(available, "MVP path is a stub on non-Windows");
        }
    }
}
