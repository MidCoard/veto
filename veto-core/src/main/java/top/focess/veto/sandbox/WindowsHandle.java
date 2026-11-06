package top.focess.veto.sandbox;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import org.jspecify.annotations.NonNull;

/**
 * An owned Win32 handle for use with try-with-resources. Unlike JNA's borrowed HANDLE value, this
 * owner reports cleanup failures. Its owner must close it once at the enclosing scope boundary; it
 * is not shared between threads.
 *
 * @param kernel native handle API binding
 * @param value valid handle whose ownership is transferred to this resource
 */
record WindowsHandle(@NonNull Kernel32 kernel, WinNT.@NonNull HANDLE value)
        implements AutoCloseable {
    @Override
    public void close() {
        if (!kernel.CloseHandle(value))
            throw new IllegalStateException("CloseHandle failed: " + kernel.GetLastError());
    }
}
