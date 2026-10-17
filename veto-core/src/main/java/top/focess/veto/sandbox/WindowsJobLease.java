package top.focess.veto.sandbox;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.sandbox.KernelSandboxSubstrate.JobObjectBasicAccountingInformation;
import top.focess.veto.sandbox.KernelSandboxSubstrate.JobObjectInfoClass;
import top.focess.veto.sandbox.KernelSandboxSubstrate.WindowsKernel32;

/** Owns one Windows Job handle until its complete process tree has retired. */
final class WindowsJobLease implements AutoCloseable {
    private final @NonNull WindowsKernel32 kernel;
    private final @NonNull Kernel32 standard;
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.sandbox.WindowsJobLease");
    private final WinNT.@NonNull HANDLE job;
    private final int pid;

    WindowsJobLease(
            @NonNull WindowsKernel32 kernel,
            @NonNull Kernel32 standard,
            WinNT.@NonNull HANDLE job,
            int pid) {
        this.kernel = kernel;
        this.standard = standard;
        this.job = job;
        this.pid = pid;
    }

    @Override
    @SuppressWarnings(
            "BusyWait") // WHY: bounded native accounting must prove every Job descendant has
    // stopped.
    public void close() {
        try {
            if (!kernel.TerminateJobObject(job, 1))
                throw new IllegalStateException(
                        "TerminateJobObject failed: " + standard.GetLastError());
            var accounting = new JobObjectBasicAccountingInformation();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                if (!kernel.QueryInformationJobObject(
                        job,
                        JobObjectInfoClass.JobObjectBasicAccountingInformation,
                        accounting,
                        accounting.size(),
                        null))
                    throw new IllegalStateException(
                            "QueryInformationJobObject failed: " + standard.GetLastError());
                accounting.read();
                if (accounting.ActiveProcesses == 0) break;
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("Windows process tree did not terminate");
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Windows process tree retirement interrupted", interrupted);
                }
            }
        } catch (Throwable failure) {
            if (!standard.CloseHandle(job))
                failure.addSuppressed(
                        new IllegalStateException(
                                "CloseHandle(Job) failed: " + standard.GetLastError()));
            throw failure;
        }
        if (!standard.CloseHandle(job))
            throw new IllegalStateException("CloseHandle(Job) failed: " + standard.GetLastError());
        log.debug("KernelSandboxSubstrate: retired Job tree for pid {}", pid);
    }
}
