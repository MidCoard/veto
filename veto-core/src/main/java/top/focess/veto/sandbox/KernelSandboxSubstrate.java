package top.focess.veto.sandbox;

import static top.focess.veto.util.LogValues.safe;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.ToolDocs;

/**
 * Kernel-level process containment used by {@link ConstrainedSubprocessSubstrate}.
 *
 * <p>Platform support:
 *
 * <ul>
 *   <li><b>Windows</b>: Job Object plus a zero-capability AppContainer launcher. The Job enforces
 *       process-tree lifetime, aggregate memory, CPU rate, active-process count, and UI
 *       restrictions; the launcher removes privileges and disables administrator SIDs before the
 *       target starts. Failure is surfaced to the caller.
 *   <li><b>macOS</b>: an Apple {@code sandbox-exec}/Seatbelt compatibility backend applies a
 *       default-deny profile before the target is executed. This is distinct from signed App
 *       Sandbox entitlements and is available only while the OS ships the compatibility tool.
 *   <li><b>Linux</b>: Bubblewrap establishes user/PID/IPC/network namespaces and a read-only host
 *       mount view with explicit workspace writes. An inner trusted bootstrap applies {@code
 *       no_new_privs} and seccomp before the target starts. Bubblewrap absence fails closed.
 * </ul>
 *
 * <p>On Windows, untrusted target code is started through {@link #prepareCommand(List,
 * SandboxProfile)}. The trusted bootstrap waits at a kernel event until the parent has attached it
 * to the Job Object, so the target cannot race the attachment. The bootstrap then creates a
 * AppContainer target before starting it. The stable AppContainer SID, an inheritable workspace
 * ACL, and a zero-capability network posture provide the filesystem and network boundary.
 */
@Component
public class KernelSandboxSubstrate {

    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int READY_TIMEOUT_MILLIS = 10_000;
    private static final int READY_POLL_MILLIS = 100;

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.sandbox.KernelSandboxSubstrate");

    /** Detect the current OS at class-load time. */
    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    private static final boolean IS_WINDOWS = OS.contains("win");
    private static final boolean IS_MACOS = OS.contains("mac");
    private static final boolean IS_LINUX = OS.contains("linux");
    private final WindowsKernel32 windowsKernel;
    private final Kernel32 windowsStdKernel;
    private final boolean windowsAppContainerLaunchAvailable;
    private final WindowsWorkspaceSecurity windowsWorkspaceSecurity;
    private final @NonNull MacOsSeatbeltSandbox macOsSeatbelt = new MacOsSeatbeltSandbox();
    private final @NonNull LinuxBubblewrapSandbox linuxBubblewrap = new LinuxBubblewrapSandbox();

    public KernelSandboxSubstrate() {
        WindowsKernel32 w = null;
        Kernel32 wk = null;
        boolean appContainerLaunch = false;
        WindowsWorkspaceSecurity workspaceSecurity = null;
        try {
            if (IS_WINDOWS) {
                w = Native.load("kernel32", ToolDocs.nonNullClass(WindowsKernel32.class));
                wk = Native.load("kernel32", ToolDocs.nonNullClass(Kernel32.class));
                appContainerLaunch = SandboxBootstrap.isAppContainerLaunchAvailable();
                workspaceSecurity = new WindowsWorkspaceSecurity();
                if (appContainerLaunch) {
                    log.info("KernelSandboxSubstrate: Windows Job/AppContainer APIs loaded");
                } else {
                    log.warn("KernelSandboxSubstrate: Windows AppContainer APIs unavailable");
                }
            } else if (!IS_MACOS && !IS_LINUX) {
                log.warn(
                        "KernelSandboxSubstrate: unsupported platform {} — kernel wall disabled",
                        OS);
            }
        } catch (Throwable t) {
            log.warn("KernelSandboxSubstrate: JNA load failed: {}", safe(t.getMessage()));
        }
        this.windowsKernel = w;
        this.windowsStdKernel = wk;
        this.windowsAppContainerLaunchAvailable = appContainerLaunch;
        this.windowsWorkspaceSecurity = workspaceSecurity;
    }

    /** Whether this substrate can actually attach an implemented kernel wall on this platform. */
    public boolean isAvailable() {
        if (IS_MACOS) {
            return macOsSeatbelt.isAvailable();
        }
        if (IS_LINUX) {
            return linuxBubblewrap.isAvailable();
        }
        if (!IS_WINDOWS) {
            return false;
        }
        return windowsKernel != null
                && windowsStdKernel != null
                && windowsAppContainerLaunchAvailable
                && windowsWorkspaceSecurity != null
                && windowsWorkspaceSecurity.isAvailable();
    }

    /** Provisions the platform filesystem identity needed before a process may be prepared. */
    public void provisionWorkspace(@NonNull SandboxProfile profile) {
        if (!IS_WINDOWS) {
            return;
        }
        WindowsWorkspaceSecurity security = windowsWorkspaceSecurity;
        if (security == null) {
            throw new IllegalStateException("Windows workspace ACL substrate is unavailable");
        }
        security.provision(profile);
    }

    /** Releases platform filesystem state owned by one provisioned profile. */
    public void deprovisionWorkspace(@NonNull SandboxProfile profile) {
        if (!IS_WINDOWS) {
            return;
        }
        WindowsWorkspaceSecurity security = windowsWorkspaceSecurity;
        if (security != null) {
            security.deprovision(profile);
        }
    }

    /**
     * Attach a spawned trusted bootstrap to the kernel process wall and retain the wall until the
     * process exits. Unsupported platforms and attachment failures fail closed.
     */
    @SuppressWarnings("resource") // The process-exit callback owns this async handle.
    public void attach(@NonNull Process process, @NonNull SandboxProfile profile) {
        AutoCloseable handle = attachRequired(process, profile);
        process.onExit()
                .whenComplete(
                        (ignoredProcess, ignoredFailure) -> {
                            try {
                                handle.close();
                            } catch (Exception e) {
                                log.warn(
                                        "KernelSandboxSubstrate: failed to close kernel handle", e);
                            }
                        });
    }

    /**
     * Attach the kernel-level process wall and return a handle that must be closed after the
     * process exits. On Windows, closing the handle closes the Job Object and terminates any
     * remaining process tree. Unsupported platforms and attachment failures throw.
     *
     * <p><b>Usage:</b>
     *
     * <pre>{@code
     * Process p = pb.start();
     * try (AutoCloseable handle = substrate.attachRequired(p, profile)) {
     *     p.waitFor();
     * } // handle closed here
     * }</pre>
     *
     * @param process the spawned process to attach to; must not be null
     * @param profile resource limits applied to the process tree
     * @return the non-null Job Object lifecycle handle
     */
    public @NonNull AutoCloseable attachRequired(
            @NonNull Process process, @NonNull SandboxProfile profile) {
        if ((IS_MACOS && macOsSeatbelt.isAvailable())
                || (IS_LINUX && linuxBubblewrap.isAvailable())) {
            return () -> {};
        }
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "No implemented kernel sandbox wall is available on platform " + OS);
        }
        AutoCloseable handle = attachWindowsJobObjectWithHandle(process, profile);
        if (handle == null) {
            throw new IllegalStateException("Windows Job Object attachment failed");
        }
        return handle;
    }

    /**
     * Wrap an approved target in Veto's trusted two-hop bootstrap. The returned command starts only
     * the bootstrap; the caller must wait for readiness, attach the bootstrap to its Job Object,
     * and then release the gate. This closes the post-spawn race for untrusted target code.
     */
    public @NonNull PreparedCommand prepareCommand(
            @NonNull List<@NonNull String> targetCommand, @NonNull SandboxProfile profile) {
        return prepareCommand(targetCommand, profile, profile.workspaceRoot());
    }

    public @NonNull PreparedCommand prepareCommand(
            @NonNull List<@NonNull String> targetCommand,
            @NonNull SandboxProfile profile,
            @NonNull Path cwd) {
        if (!isAvailable()) {
            throw new IllegalStateException("OS sandbox is unavailable on platform " + OS);
        }
        if (targetCommand.isEmpty()) {
            throw new IllegalArgumentException("targetCommand must not be empty");
        }
        if (IS_MACOS) {
            return new PreparedCommand(
                    macOsSeatbelt.wrap(targetCommand, profile), null, null, false);
        }
        if (IS_LINUX) {
            return new PreparedCommand(
                    linuxBubblewrap.wrap(targetCommand, profile, cwd), null, null, false);
        }
        WindowsKernel32 kernel = requiredWindowsKernel();
        Kernel32 standardKernel = requiredWindowsStdKernel();
        WindowsWorkspaceSecurity security = windowsWorkspaceSecurity;
        if (security == null) {
            throw new IllegalStateException("Windows workspace ACL substrate is unavailable");
        }
        Path targetExecutable = Path.of(targetCommand.get(0));
        if (targetExecutable.isAbsolute()) {
            security.provisionExecutable(targetExecutable, profile);
        }
        String suffix = UUID.randomUUID().toString();
        String gateName = "Local\\VetoSandboxGate-" + suffix;
        String readyName = "Local\\VetoSandboxReady-" + suffix;
        String appContainerName =
                WindowsWorkspaceSecurity.appContainerName(profile.workspaceRoot());
        WinNT.HANDLE gate = kernel.CreateEventW(null, false, false, new WString(gateName));
        if (gate == null || !isValidHandle(gate)) {
            throw new IllegalStateException("CreateEventW(gate) failed");
        }
        gate = requireHandle(gate, "CreateEventW(gate)");
        WinNT.HANDLE ready = kernel.CreateEventW(null, false, false, new WString(readyName));
        if (ready == null || !isValidHandle(ready)) {
            standardKernel.CloseHandle(gate);
            throw new IllegalStateException("CreateEventW(ready) failed");
        }
        List<String> wrapped = bootstrapInvocation();
        wrapped.add(SandboxBootstrap.MARKER);
        wrapped.add(gateName);
        wrapped.add(readyName);
        wrapped.add(appContainerName);
        wrapped.add(profile.networkAllowed() ? "allow-network" : "deny-network");
        wrapped.add("--");
        wrapped.addAll(targetCommand);
        return new PreparedCommand(wrapped, gate, ready, true);
    }

    private @NonNull WindowsKernel32 requiredWindowsKernel() {
        WindowsKernel32 kernel = windowsKernel;
        if (kernel == null) {
            throw new IllegalStateException("Windows kernel32 sandbox binding is unavailable");
        }
        return kernel;
    }

    private @NonNull Kernel32 requiredWindowsStdKernel() {
        Kernel32 kernel = windowsStdKernel;
        if (kernel == null) {
            throw new IllegalStateException("Windows kernel32 standard binding is unavailable");
        }
        return kernel;
    }

    private static boolean isValidHandle(WinNT.@NonNull HANDLE handle) {
        if (handle.getPointer() == null) {
            return false;
        }
        long address = Pointer.nativeValue(handle.getPointer());
        return address != 0 && address != -1L;
    }

    private static WinNT.@NonNull HANDLE requireHandle(
            WinNT.HANDLE handle, @NonNull String operation) {
        if (handle == null || !isValidHandle(handle)) {
            throw new IllegalStateException(operation + " returned an invalid handle");
        }
        return handle;
    }

    private static @NonNull List<String> bootstrapInvocation() {
        return new ArrayList<>(SandboxBootstrap.processInvocation());
    }

    /** Parent-owned handles for one two-hop launch. */
    public final class PreparedCommand implements AutoCloseable {
        private final @NonNull List<@NonNull String> command;
        private final boolean gated;
        private WinNT.HANDLE gate;
        private WinNT.HANDLE ready;

        private PreparedCommand(
                @NonNull List<@NonNull String> command,
                WinNT.HANDLE gate,
                WinNT.HANDLE ready,
                boolean gated) {
            this.command = List.copyOf(command);
            this.gate = gate;
            this.ready = ready;
            this.gated = gated;
        }

        public @NonNull List<@NonNull String> command() {
            return command;
        }

        public void awaitReady(@NonNull Process bootstrap) {
            if (!gated) {
                return;
            }
            WinNT.HANDLE handle = ready;
            if (handle == null) {
                throw new IllegalStateException("Sandbox readiness handle is closed");
            }
            long deadline = System.nanoTime() + READY_TIMEOUT_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadline) {
                int wait = requiredWindowsKernel().WaitForSingleObject(handle, READY_POLL_MILLIS);
                if (wait == WAIT_OBJECT_0) {
                    return;
                }
                if (wait != WAIT_TIMEOUT) {
                    throw new IllegalStateException("Sandbox bootstrap readiness failed: " + wait);
                }
                if (!bootstrap.isAlive()) {
                    throw new IllegalStateException(
                            "Sandbox bootstrap exited before readiness with code "
                                    + bootstrap.exitValue());
                }
            }
            throw new IllegalStateException("Sandbox bootstrap readiness timed out");
        }

        public void release() {
            if (!gated) {
                close();
                return;
            }
            WinNT.HANDLE handle = gate;
            if (handle == null) {
                throw new IllegalStateException("Sandbox launch gate is closed");
            }
            if (!requiredWindowsKernel().SetEvent(handle)) {
                throw new IllegalStateException(
                        "SetEvent failed with Win32 error "
                                + requiredWindowsStdKernel().GetLastError());
            }
            close();
        }

        @Override
        public void close() {
            WinNT.HANDLE gateHandle = gate;
            WinNT.HANDLE readyHandle = ready;
            gate = null;
            ready = null;
            if (gateHandle != null) {
                requiredWindowsStdKernel().CloseHandle(gateHandle);
            }
            if (readyHandle != null) {
                requiredWindowsStdKernel().CloseHandle(readyHandle);
            }
        }
    }

    /**
     * Attach Windows Job Object and return a closeable handle. The handle should be closed after
     * the process exits to release the kernel Job Object resource.
     */
    private AutoCloseable attachWindowsJobObjectWithHandle(
            @NonNull Process process, @NonNull SandboxProfile profile) {
        WindowsKernel32 kernel = windowsKernel;
        Kernel32 kernel32 = windowsStdKernel;
        if (kernel == null || kernel32 == null) {
            log.warn("KernelSandboxSubstrate: Windows kernel32 is unavailable");
            return null;
        }
        WinNT.HANDLE job = null;
        WinNT.HANDLE processHandle = null;
        try {
            job = kernel.CreateJobObjectW(null, null);
            if (job == null) {
                log.warn(
                        "KernelSandboxSubstrate: CreateJobObjectW failed (Win32 error={})",
                        kernel32.GetLastError());
                return null;
            }
            Pointer jobPtr = job.getPointer();
            if (jobPtr == null) {
                log.warn(
                        "KernelSandboxSubstrate: CreateJobObjectW returned handle with null"
                                + " pointer");
                return null;
            }
            JobObjectExtendedLimitInformation info = extendedLimits(profile);
            if (!kernel.SetInformationJobObject(
                    job, JobObjectInfoClass.JobObjectExtendedLimitInformation, info, info.size())) {
                log.warn(
                        "KernelSandboxSubstrate: extended Job limits failed (Win32 error={})",
                        kernel32.GetLastError());
                return null;
            }
            JobObjectCpuRateControlInformation cpuInfo = cpuLimits(profile);
            if (!kernel.SetInformationJobObject(
                    job,
                    JobObjectInfoClass.JobObjectCpuRateControlInformation,
                    cpuInfo,
                    cpuInfo.size())) {
                log.warn(
                        "KernelSandboxSubstrate: CPU Job limit failed (Win32 error={})",
                        kernel32.GetLastError());
                return null;
            }
            JobObjectBasicUiRestrictions uiInfo = uiLimits();
            if (!kernel.SetInformationJobObject(
                    job, JobObjectInfoClass.JobObjectBasicUiRestrictions, uiInfo, uiInfo.size())) {
                log.warn(
                        "KernelSandboxSubstrate: UI Job restrictions failed (Win32 error={})",
                        kernel32.GetLastError());
                return null;
            }
            int pid = getProcessId(process);
            if (pid <= 0) {
                log.warn("KernelSandboxSubstrate: could not determine child pid");
                return null;
            }
            processHandle =
                    kernel32.OpenProcess(
                            ProcessAccess.PROCESS_SET_QUOTA | ProcessAccess.PROCESS_TERMINATE,
                            false,
                            pid);
            Pointer handlePtr = processHandle == null ? null : processHandle.getPointer();
            if (processHandle == null || handlePtr == null) {
                log.warn(
                        "KernelSandboxSubstrate: OpenProcess failed for pid {} (Win32 error={})",
                        pid,
                        kernel32.GetLastError());
                return null;
            }
            // Check for null pointer (0) and INVALID_HANDLE_VALUE (-1)
            long handleAddr = Pointer.nativeValue(handlePtr);
            if (handleAddr == 0 || handleAddr == -1L) {
                log.warn(
                        "KernelSandboxSubstrate: OpenProcess returned invalid handle for pid {}"
                                + " (addr={})",
                        pid,
                        handleAddr);
                return null;
            }
            if (!kernel.AssignProcessToJobObject(job, processHandle)) {
                log.warn(
                        "KernelSandboxSubstrate: AssignProcessToJobObject failed (Win32 error={})",
                        kernel32.GetLastError());
                return null;
            }
            kernel32.CloseHandle(processHandle);
            processHandle = null;
            log.info(
                    "KernelSandboxSubstrate: attached Windows Job Object to pid {} "
                            + "(memory={} MiB, cpu={}%, processes={})",
                    pid, profile.maxMemoryMb(), profile.maxCpuPercent(), profile.maxProcesses());
            JobHandle result = new JobHandle(job, pid);
            job = null;
            return result;
        } catch (Throwable t) {
            log.warn("KernelSandboxSubstrate: Windows attach failed: {}", safe(t.getMessage()));
            return null;
        } finally {
            if (processHandle != null) {
                kernel32.CloseHandle(processHandle);
            }
            if (job != null) {
                kernel32.CloseHandle(job);
            }
        }
    }

    static @NonNull JobObjectExtendedLimitInformation extendedLimits(
            @NonNull SandboxProfile profile) {
        JobObjectExtendedLimitInformation info = new JobObjectExtendedLimitInformation();
        info.BasicLimitInformation.LimitFlags =
                JobObjectLimit.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                        | JobObjectLimit.JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION
                        | JobObjectLimit.JOB_OBJECT_LIMIT_ACTIVE_PROCESS
                        | JobObjectLimit.JOB_OBJECT_LIMIT_JOB_MEMORY;
        info.BasicLimitInformation.ActiveProcessLimit = profile.maxProcesses();
        info.JobMemoryLimit =
                new BaseTSD.SIZE_T(Math.multiplyExact(profile.maxMemoryMb(), 1_048_576L));
        info.write();
        return info;
    }

    static @NonNull JobObjectCpuRateControlInformation cpuLimits(@NonNull SandboxProfile profile) {
        JobObjectCpuRateControlInformation info = new JobObjectCpuRateControlInformation();
        info.ControlFlags =
                JobObjectCpuRateControl.JOB_OBJECT_CPU_RATE_CONTROL_ENABLE
                        | JobObjectCpuRateControl.JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;
        info.CpuRate = profile.maxCpuPercent() * 100;
        info.write();
        return info;
    }

    static @NonNull JobObjectBasicUiRestrictions uiLimits() {
        JobObjectBasicUiRestrictions info = new JobObjectBasicUiRestrictions();
        info.UIRestrictionsClass = JobObjectUiLimit.JOB_OBJECT_UILIMIT_ALL;
        info.write();
        return info;
    }

    /** A closeable wrapper for a Windows Job Object handle. */
    private final class JobHandle implements AutoCloseable {
        private final WinNT.@NonNull HANDLE job;
        private final int pid;

        JobHandle(WinNT.@NonNull HANDLE job, int pid) {
            this.job = job;
            this.pid = pid;
        }

        @Override
        public void close() {
            if (windowsStdKernel != null) {
                try {
                    windowsStdKernel.CloseHandle(job);
                    log.debug("KernelSandboxSubstrate: closed Job handle for pid {}", pid);
                } catch (Throwable t) {
                    log.warn(
                            "KernelSandboxSubstrate: CloseHandle failed: {}", safe(t.getMessage()));
                }
            }
        }
    }

    /**
     * Cross-platform process-id extraction via the public {@link Process#pid()} API (Java 9+).
     * Returns -1 if the process is null or the pid cannot be coerced to a positive int.
     */
    private static int getProcessId(Process process) {
        if (process == null) {
            return -1;
        }
        try {
            long pid = process.pid();
            if (pid > 0 && pid <= Integer.MAX_VALUE) {
                return (int) pid;
            }
        } catch (Throwable t) {
            log.debug("KernelSandboxSubstrate: process.pid() failed: {}", safe(t.getMessage()));
        }
        return -1;
    }

    /** Minimal JNA interface for the Windows kernel32 calls we need. */
    public interface WindowsKernel32 extends Library {
        WinNT.HANDLE CreateJobObjectW(Pointer lpJobAttributes, String lpName);

        boolean SetInformationJobObject(
                WinNT.HANDLE hJob,
                int JobObjectInfoClass,
                Structure lpJobObjectInfo,
                int cbJobObjectInfoLength);

        boolean AssignProcessToJobObject(WinNT.HANDLE hJob, WinNT.HANDLE hProcess);

        WinNT.HANDLE CreateEventW(
                Pointer eventAttributes, boolean manualReset, boolean initialState, WString name);

        boolean SetEvent(WinNT.HANDLE event);

        int WaitForSingleObject(WinNT.HANDLE handle, int milliseconds);
    }

    // --- Windows job-object structs (JOBOBJECT_EXTENDED_LIMIT_INFORMATION, WinNT.h) ---

    /** Win32 {@code JOBOBJECT_BASIC_LIMIT_INFORMATION}. */
    public static class JobObjectBasicLimitInformation extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public BaseTSD.@NonNull SIZE_T MinimumWorkingSetSize = new BaseTSD.SIZE_T();
        public BaseTSD.@NonNull SIZE_T MaximumWorkingSetSize = new BaseTSD.SIZE_T();
        public int ActiveProcessLimit;
        public BaseTSD.@NonNull ULONG_PTR Affinity = new BaseTSD.ULONG_PTR();
        public int PriorityClass;
        public int SchedulingClass;

        @Override
        protected @NonNull List<String> getFieldOrder() {
            return Arrays.asList(
                    "PerProcessUserTimeLimit",
                    "PerJobUserTimeLimit",
                    "LimitFlags",
                    "MinimumWorkingSetSize",
                    "MaximumWorkingSetSize",
                    "ActiveProcessLimit",
                    "Affinity",
                    "PriorityClass",
                    "SchedulingClass");
        }
    }

    /** Win32 {@code IO_COUNTERS} (placeholder fields for the layout JNA needs). */
    public static class IoCounters extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;

        @Override
        protected @NonNull List<String> getFieldOrder() {
            return Arrays.asList(
                    "ReadOperationCount",
                    "WriteOperationCount",
                    "OtherOperationCount",
                    "ReadTransferCount",
                    "WriteTransferCount",
                    "OtherTransferCount");
        }
    }

    /** Win32 {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION}. */
    public static class JobObjectExtendedLimitInformation extends Structure {
        public @NonNull JobObjectBasicLimitInformation BasicLimitInformation;
        public @NonNull IoCounters IoInfo;
        public BaseTSD.@NonNull SIZE_T ProcessMemoryLimit;
        public BaseTSD.@NonNull SIZE_T JobMemoryLimit;
        public BaseTSD.@NonNull SIZE_T PeakProcessMemoryUsed;
        public BaseTSD.@NonNull SIZE_T PeakJobMemoryUsed;

        public JobObjectExtendedLimitInformation() {
            BasicLimitInformation = new JobObjectBasicLimitInformation();
            IoInfo = new IoCounters();
            ProcessMemoryLimit = new BaseTSD.SIZE_T();
            JobMemoryLimit = new BaseTSD.SIZE_T();
            PeakProcessMemoryUsed = new BaseTSD.SIZE_T();
            PeakJobMemoryUsed = new BaseTSD.SIZE_T();
        }

        @Override
        protected @NonNull List<String> getFieldOrder() {
            return Arrays.asList(
                    "BasicLimitInformation",
                    "IoInfo",
                    "ProcessMemoryLimit",
                    "JobMemoryLimit",
                    "PeakProcessMemoryUsed",
                    "PeakJobMemoryUsed");
        }
    }

    /** {@code JOBOBJECTINFOCLASS} values (subset; JobObjectExtendedLimitInformation = 9). */
    public static final class JobObjectInfoClass {
        public static final int JobObjectBasicUiRestrictions = 4;
        public static final int JobObjectExtendedLimitInformation = 9;
        public static final int JobObjectCpuRateControlInformation = 15;

        private JobObjectInfoClass() {}
    }

    /** {@code JOBOBJECT_LIMIT} flags (subset). */
    public static final class JobObjectLimit {
        public static final int JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
        public static final int JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
        public static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
        public static final int JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION = 0x00000400;

        private JobObjectLimit() {}
    }

    public static class JobObjectCpuRateControlInformation extends Structure {
        public int ControlFlags;
        public int CpuRate;

        @Override
        protected @NonNull List<String> getFieldOrder() {
            return Arrays.asList("ControlFlags", "CpuRate");
        }
    }

    public static class JobObjectBasicUiRestrictions extends Structure {
        public int UIRestrictionsClass;

        @Override
        protected @NonNull List<String> getFieldOrder() {
            return List.of("UIRestrictionsClass");
        }
    }

    public static final class JobObjectCpuRateControl {
        public static final int JOB_OBJECT_CPU_RATE_CONTROL_ENABLE = 0x1;
        public static final int JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP = 0x4;

        private JobObjectCpuRateControl() {}
    }

    public static final class JobObjectUiLimit {
        public static final int JOB_OBJECT_UILIMIT_ALL = 0x000000FF;

        private JobObjectUiLimit() {}
    }

    public static final class ProcessAccess {
        public static final int PROCESS_TERMINATE = 0x0001;
        public static final int PROCESS_SET_QUOTA = 0x0100;

        private ProcessAccess() {}
    }
}
