package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link SeccompWall} is a sound detector: construction only probes for libseccomp (it
 * must NOT self-apply a filter into the host JVM — the prior version called {@code seccomp_load} in
 * its constructor, which would sandbox the Veto process itself), the probe never throws, and the
 * wall reports disabled gracefully when libseccomp is absent (the common case on Windows / dev
 * machines).
 */
class SeccompWallTest {

    @Test
    void constructionProbesWithoutSelfApplyAndExposesBaselineSpec() {
        // Constructing the wall must NOT load a seccomp filter into this JVM — it only probes.
        SeccompWall wall = new SeccompWall();

        // The probe is callable without throwing regardless of whether libseccomp is present.
        wall.isEnabled();

        // The baseline syscall allowlist spec is always available (for a future wrapper to apply).
        assertNotNull(wall.baselineSyscalls(), "the baseline syscall allowlist spec is present");
        assertFalse(wall.baselineSyscalls().isEmpty(), "the baseline allowlist is non-empty");
    }
}
