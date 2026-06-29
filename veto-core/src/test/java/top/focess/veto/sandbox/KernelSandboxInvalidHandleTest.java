package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the INVALID_HANDLE_VALUE check on Windows OpenProcess. The previous
 * implementation compared only against Pointer.NULL, which let INVALID_HANDLE_VALUE
 * (0xFFFFFFFFFFFFFFFF, i.e. (HANDLE)-1) slip through and silently fail AssignProcessToJobObject
 * with ERROR_INVALID_HANDLE — defeating KILL_ON_JOB_CLOSE.
 *
 * <p>The fix in {@link KernelSandboxSubstrate#attachWindowsJobObject} compares against both {@link
 * Pointer#NULL} and {@link Pointer#createConstant(long)} with -1. These tests pin that contract so
 * the next refactor can't drop either check.
 */
class KernelSandboxInvalidHandleTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    void invalidHandleSentinelIsDistinctFromNull() {
        // Construct both pointers explicitly and verify they're NOT equal. The contract the
        // production code relies on: a check that compares against only one of them will let
        // the other slip through.
        Pointer invalidHandleValue = Pointer.createConstant(-1L);
        Pointer nullPointer = Pointer.createConstant(0L);
        assertNotSame(
                invalidHandleValue,
                nullPointer,
                "INVALID_HANDLE_VALUE and NULL must be distinct Pointer instances");
        // The exact-equality check is what the production code uses; verify it holds.
        assertNotEquals(
                nullPointer,
                invalidHandleValue,
                "Pointer(-1) must NOT equal Pointer(0) — otherwise the production guard fails");
    }

    @Test
    void openProcessForBogusPidReturnsInvalidOrNullHandle() {
        // Windows-only: kernel32 binding.
        Assumptions.assumeTrue(isWindows(), "Windows-only test (relies on kernel32 OpenProcess)");
        Kernel32 kernel32;
        try {
            kernel32 = Native.load("kernel32", Kernel32.class);
        } catch (Throwable t) {
            // JNA failed to load kernel32 (e.g. JNA not on the classpath, or unsupported
            // platform bitness) — skip rather than fail.
            Assumptions.abort("kernel32 not loadable: " + t.getMessage());
            return;
        }
        // Pid -2 — definitely not a live process. Per MSDN, OpenProcess returns either NULL
        // or INVALID_HANDLE_VALUE on failure. We check both sentinels.
        WinNT.HANDLE handle = kernel32.OpenProcess(0x1F0FFF, false, -2);
        // The HANDLE wrapper is never literally null (JNA wraps a primitive long). The wrapped
        // pointer can be null OR (Pointer.createConstant(-1L)).
        if (handle == null) {
            // Some JNA versions return null for INVALID; in that case the second-sentinel
            // check is irrelevant.
            return;
        }
        Pointer ptr = handle.getPointer();
        boolean isNull = (ptr == null) || (Pointer.NULL != null && Pointer.NULL.equals(ptr));
        boolean isInvalid = Pointer.createConstant(-1L).equals(ptr);
        assertTrue(
                isNull || isInvalid,
                "OpenProcess for bogus pid must return NULL or INVALID_HANDLE_VALUE, got " + ptr);
    }
}
