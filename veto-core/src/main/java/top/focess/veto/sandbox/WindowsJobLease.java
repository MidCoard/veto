package top.focess.veto.sandbox;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTRByReference;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.sandbox.KernelSandboxSubstrate.JobObjectBasicAccountingInformation;
import top.focess.veto.sandbox.KernelSandboxSubstrate.JobObjectInfoClass;
import top.focess.veto.sandbox.KernelSandboxSubstrate.WindowsKernel32;

/**
 * Owns a Windows Job and its private completion port until the complete process tree exits. The
 * existing process-exit callback is the sole owner and calls close once. No monitor thread or
 * shared mutable state is needed.
 *
 * <p>The port must be associated before the first process enters the Job. NEW_PROCESS messages
 * identify processes to wait for, but Windows does not guarantee notification delivery. Job
 * accounting therefore checks coverage; it never substitutes for waiting on process handles.
 */
final class WindowsJobLease implements AutoCloseable {
    private static final int JOB_OBJECT_MSG_NEW_PROCESS = 6;
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.sandbox.WindowsJobLease");

    private final @NonNull WindowsKernel32 kernel;
    private final @NonNull Kernel32 standard;
    private final @NonNull WindowsHandle job;
    private final @NonNull WindowsHandle completionPort;
    private final int pid;

    /**
     * Transfers the attached Job and its already-associated port to this lease.
     *
     * @param kernel Job API binding
     * @param standard handle, completion-port and process API binding
     * @param job attached Job, with breakaway disabled
     * @param completionPort private port associated while the Job was empty
     * @param pid bootstrap process ID, used only for diagnostics
     */
    WindowsJobLease(
            @NonNull WindowsKernel32 kernel,
            @NonNull Kernel32 standard,
            WinNT.@NonNull HANDLE job,
            WinNT.@NonNull HANDLE completionPort,
            int pid) {
        this.kernel = kernel;
        this.standard = standard;
        this.job = new WindowsHandle(standard, job);
        this.completionPort = new WindowsHandle(standard, completionPort);
        this.pid = pid;
    }

    /**
     * Terminates the Job and waits up to ten seconds for complete native retirement. Failure
     * propagates to the substrate, which retains filesystem exclusions. Both owned handles close
     * even when termination, coverage, or a process wait fails.
     */
    @Override
    public void close() {
        try (job;
                completionPort) {
            if (!kernel.TerminateJobObject(job.value(), 1))
                throw new IllegalStateException(
                        "TerminateJobObject failed: " + standard.GetLastError());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            var message = new IntByReference();
            var key = new ULONG_PTRByReference();
            var processId = new PointerByReference();
            var accounting = new JobObjectBasicAccountingInformation();
            long retired = 0;
            while (true) {
                if (!standard.GetQueuedCompletionStatus(
                        completionPort.value(), message, key, processId, remainingMillis(deadline)))
                    throw new IllegalStateException(
                            "Job retirement notifications incomplete after "
                                    + retired
                                    + " processes: "
                                    + standard.GetLastError());
                if (message.getValue() != JOB_OBJECT_MSG_NEW_PROCESS) continue;

                int processPid = (int) Pointer.nativeValue(processId.getValue());
                var handle = kernel.OpenProcess(WinNT.SYNCHRONIZE, false, processPid);
                if (handle == null) {
                    // A vanished PID has no remaining process object. A recycled PID may make
                    // us wait for its replacement, but we never terminate a process by PID.
                    int error = standard.GetLastError();
                    if (error != WinError.ERROR_INVALID_PARAMETER)
                        throw new IllegalStateException(
                                "OpenProcess for retirement failed: " + error);
                } else {
                    try (var process = new WindowsHandle(standard, handle)) {
                        int result =
                                kernel.WaitForSingleObject(
                                        process.value(), remainingMillis(deadline));
                        if (result != 0)
                            throw new IllegalStateException(
                                    "Native process retirement wait failed: result="
                                            + result
                                            + (result == -1
                                                    ? ", error=" + standard.GetLastError()
                                                    : ""));
                    }
                }
                retired++;
                if (!kernel.QueryInformationJobObject(
                        job.value(),
                        JobObjectInfoClass.JobObjectBasicAccountingInformation,
                        accounting,
                        accounting.size(),
                        null))
                    throw new IllegalStateException(
                            "QueryInformationJobObject failed: " + standard.GetLastError());
                accounting.read();
                // Query after each native wait: children created during termination must also
                // have a record and complete their own wait before this barrier can succeed.
                if (accounting.ActiveProcesses == 0
                        && retired == Integer.toUnsignedLong(accounting.TotalProcesses)) break;
            }
        }
        log.debug("KernelSandboxSubstrate: retired Job tree for pid {}", pid);
    }

    /**
     * Converts the shared deadline to a bounded native wait; repeated messages cannot extend it.
     *
     * @param deadline monotonic retirement deadline in nanoseconds
     * @return remaining wait rounded up to at least one millisecond
     */
    private static int remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalStateException("Windows process tree did not retire");
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}
