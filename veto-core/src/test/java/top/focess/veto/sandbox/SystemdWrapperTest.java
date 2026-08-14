package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/**
 * Tests for systemd-run wrapper for Linux seccomp enforcement. The wrapper detects systemd-run on
 * PATH and wraps the command with SystemCallFilter= and resource limits. Real enforcement is
 * Linux-only; these tests verify the detection and wrapping logic.
 */
class SystemdWrapperTest {

    /**
     * When systemd-run is available on PATH, the wrapper should construct the correct argv with
     * SystemCallFilter, MemoryMax, and PrivateMountNamespace.
     */
    @Test
    void systemdRunAvailableWrapsCommandWithSeccompFilter() {
        // Simulate systemd-run on PATH
        String fakeSystemdRun = "/usr/bin/systemd-run";

        List<String> originalCommand = List.of("python3", "-c", "print('hello')");
        List<String> wrapped =
                KernelSandboxSubstrate.wrapWithSystemdRun(
                        originalCommand,
                        fakeSystemdRun,
                        List.of(0, 1, 2, 3, 9, 12, 35, 39, 60, 231));

        // Verify the wrapper structure
        assertEquals(fakeSystemdRun, wrapped.get(0), "First arg should be systemd-run");
        assertTrue(wrapped.contains("--same-dir"), "Should include --same-dir");
        assertTrue(wrapped.contains("--collect"), "Should include --collect");
        assertTrue(
                wrapped.stream().anyMatch(s -> s.contains("SystemCallFilter=")),
                "Should include SystemCallFilter property");
        assertTrue(
                wrapped.stream().anyMatch(s -> s.contains("MemoryMax=")),
                "Should include MemoryMax property");
        assertTrue(
                wrapped.stream().anyMatch(s -> s.contains("PrivateMountNamespace")),
                "Should include PrivateMountNamespace");

        // Verify the original command is at the end
        int sepIndex = wrapped.indexOf("--");
        assertTrue(sepIndex > 0, "Should have -- separator before command");
        List<String> commandAfterSep = wrapped.subList(sepIndex + 1, wrapped.size());
        assertEquals(originalCommand, commandAfterSep, "Original command should follow --");
    }

    /**
     * When systemd-run is NOT available, the wrapper should return the original command unchanged.
     */
    @Test
    void systemdRunNotAvailableReturnsOriginalCommand() {
        List<String> originalCommand = List.of("python3", "-c", "print('hello')");
        List<String> wrapped =
                KernelSandboxSubstrate.wrapWithSystemdRun(
                        originalCommand, null, List.of(0, 1, 2, 3));

        assertEquals(
                originalCommand,
                wrapped,
                "Should return original command when systemd-run unavailable");
    }

    /**
     * The SystemCallFilter property should contain the syscall numbers from the baseline allowlist.
     */
    @Test
    void systemCallFilterContainsBaselineSyscalls() {
        String fakeSystemdRun = "/usr/bin/systemd-run";
        List<Integer> baseline = List.of(0, 1, 2, 3, 9, 60, 231);

        List<String> wrapped =
                KernelSandboxSubstrate.wrapWithSystemdRun(List.of("ls"), fakeSystemdRun, baseline);

        @NonNull String syscallFilter =
                wrapped.stream()
                        .filter(s -> s.contains("SystemCallFilter="))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("Should have SystemCallFilter property"));
        // systemd-run uses syscall names, not numbers
        assertTrue(syscallFilter.contains("read"), "Should include 'read' syscall name");
        assertTrue(syscallFilter.contains("write"), "Should include 'write' syscall name");
        assertTrue(syscallFilter.contains("open"), "Should include 'open' syscall name");
        assertTrue(syscallFilter.contains("close"), "Should include 'close' syscall name");
    }
}
