package top.focess.veto.sandbox;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Part 5 §4.1 kernel-level sandbox hard wall — a wall-attacher that wraps a {@link
 * ConstrainedSubprocessSubstrate} and attaches the spawned process to a kernel-level isolation
 * container after the constrained-subprocess flow has started the process. The wall itself is a
 * no-op when the platform doesn't support the relevant JNA bindings (or when JNA is unavailable);
 * the substrate falls back to constrained-subprocess.
 *
 * <p>Platform support:
 *
 * <ul>
 *   <li><b>Windows</b>: Job Object (with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}) via kernel32.
 *       The child process is pinned to the Job — if the Veto process crashes, the child is killed
 *       automatically.
 *   <li><b>Linux</b>: namespaces (mount/pid/net) + seccomp syscall allowlist + cgroup memory/CPU
 *       limits. The MVP path is a stub: libseccomp / libcgroup are not on the classpath; production
 *       would add the native libs and the unshare(2) + seccomp(2) + cgroup writes.
 * </ul>
 *
 * <p>Usage: {@code new KernelSandboxSubstrate().attach(Process)}. The Process is the
 * already-spawned child (the constrained-substrate has done argv[] exec). The attach call returns
 * quickly; the kernel wall lives for the life of the child process.
 */
@Component
public class KernelSandboxSubstrate {

    private static final Logger log = LoggerFactory.getLogger(KernelSandboxSubstrate.class);

    /** Detect the current OS at class-load time. */
    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    private static final boolean IS_WINDOWS = OS.contains("win");
    private static final boolean IS_LINUX =
            OS.contains("nix") || OS.contains("nux") || OS.contains("linux");

    private final WindowsKernel32 windowsKernel;
    private final LinuxLibc linuxLibc;
    private final Kernel32 windowsStdKernel;

    public KernelSandboxSubstrate() {
        WindowsKernel32 w = null;
        LinuxLibc l = null;
        Kernel32 wk = null;
        try {
            if (IS_WINDOWS) {
                w = Native.load("kernel32", WindowsKernel32.class);
                wk = Native.load("kernel32", Kernel32.class);
                log.info("KernelSandboxSubstrate: Windows kernel32 loaded");
            } else if (IS_LINUX) {
                l = Native.load("c", LinuxLibc.class);
                log.info("KernelSandboxSubstrate: Linux libc loaded");
            } else {
                log.warn(
                        "KernelSandboxSubstrate: unsupported platform {} — kernel wall disabled",
                        OS);
            }
        } catch (Throwable t) {
            log.warn("KernelSandboxSubstrate: JNA load failed: {}", t.getMessage());
        }
        this.windowsKernel = w;
        this.windowsStdKernel = wk;
        this.linuxLibc = l;
    }

    /** Whether this substrate can actually attach a kernel wall on the current platform. */
    public boolean isAvailable() {
        if (IS_WINDOWS) {
            return windowsKernel != null;
        }
        if (IS_LINUX) {
            // The MVP path is a stub — production would need libseccomp + libcgroup.
            return false;
        }
        return false;
    }

    /**
     * Attach the kernel-level wall to a spawned process. The base substrate has already started the
     * process. On unsupported platforms this is a no-op.
     */
    public void attach(Process process) {
        if (process == null) {
            return;
        }
        if (IS_WINDOWS && windowsKernel != null) {
            attachWindowsJobObject(process);
        } else if (IS_LINUX) {
            // Linux path: write cgroup memory + CPU limits for the child pid. Namespaces
            // (unshare(2)) and seccomp(2) require pre-fork hooks that the Process API does
            // not expose post-exec. Production would route the spawn through a wrapper that
            // does `unshare --mount --pid --net -- <command>` then execs the command —
            // we ship a wrapper-detecting helper (see spawnLinuxWrapper below).
            attachLinuxCgroup(process);
        } else {
            log.debug("KernelSandboxSubstrate: no kernel wall available (platform={})", OS);
        }
    }

    /**
     * Helper for the Linux path: returns a {@code ProcessBuilder} that wraps the actual command in
     * {@code unshare(1)} to give the child a fresh mount/pid/net namespace. If {@code unshare} is
     * not on PATH, returns the original ProcessBuilder unchanged.
     */
    public static ProcessBuilder spawnLinuxWrapper(java.util.List<String> command) {
        if (!IS_LINUX || command == null || command.isEmpty()) {
            return new ProcessBuilder(command);
        }
        // Probe: try `which unshare` via the PATH. If absent, fall back to a direct exec.
        String unsharePath = locateOnPath("unshare");
        if (unsharePath == null) {
            log.debug("KernelSandboxSubstrate: unshare(1) not on PATH; skipping namespace wall");
            return new ProcessBuilder(command);
        }
        java.util.List<String> wrapped = new java.util.ArrayList<>();
        wrapped.add(unsharePath);
        wrapped.add("--mount");
        wrapped.add("--pid");
        wrapped.add("--fork");
        wrapped.add("--net");
        wrapped.addAll(command);
        log.info("KernelSandboxSubstrate: wrapping command in unshare: {}", wrapped);
        return new ProcessBuilder(wrapped);
    }

    /**
     * Wraps a command with systemd-run for Linux seccomp enforcement. When systemd-run is available
     * on PATH, this constructs a command that runs the original command with:
     *
     * <ul>
     *   <li>{@code SystemCallFilter=} with the baseline syscall allowlist
     *   <li>{@code MemoryMax=} to limit memory
     *   <li>{@code PrivateMountNamespace=yes} for mount isolation
     * </ul>
     *
     * <p>If systemd-run is not available (null), returns the original command unchanged.
     *
     * @param command the original command to wrap
     * @param systemdRunPath the path to systemd-run (from PATH probe), or null if not available
     * @param baselineSyscalls the syscall numbers to allow (x86_64)
     * @return the wrapped command, or the original if systemd-run is unavailable
     */
    public static java.util.List<String> wrapWithSystemdRun(
            java.util.List<String> command,
            String systemdRunPath,
            java.util.List<Integer> baselineSyscalls) {
        if (systemdRunPath == null || command == null || command.isEmpty()) {
            return command;
        }

        java.util.List<String> wrapped = new java.util.ArrayList<>();
        wrapped.add(systemdRunPath);
        wrapped.add("--same-dir");
        wrapped.add("--collect");

        // Build the SystemCallFilter property with syscall names
        String syscallFilter = "SystemCallFilter=" + syscallsToNames(baselineSyscalls);
        wrapped.add("--property=" + syscallFilter);

        // Memory limit (512 MiB)
        wrapped.add("--property=MemoryMax=512M");

        // Mount namespace isolation
        wrapped.add("--property=PrivateMountNamespace=yes");

        // Separator before the actual command
        wrapped.add("--");
        wrapped.addAll(command);

        return wrapped;
    }

    /**
     * Converts x86_64 syscall numbers to their names for systemd-run's SystemCallFilter. Production
     * would use a complete mapping; this covers the baseline allowlist.
     */
    private static String syscallsToNames(java.util.List<Integer> syscalls) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int num : syscalls) {
            String name = SYSCALL_NAMES.getOrDefault(num, "syscall_" + num);
            names.add(name);
        }
        return String.join(" ", names);
    }

    /** x86_64 syscall number to name mapping (baseline subset). */
    private static final java.util.Map<Integer, String> SYSCALL_NAMES =
            java.util.Map.ofEntries(
                    java.util.Map.entry(0, "read"),
                    java.util.Map.entry(1, "write"),
                    java.util.Map.entry(2, "open"),
                    java.util.Map.entry(3, "close"),
                    java.util.Map.entry(9, "mmap"),
                    java.util.Map.entry(12, "brk"),
                    java.util.Map.entry(35, "nanosleep"),
                    java.util.Map.entry(39, "getpid"),
                    java.util.Map.entry(60, "exit"),
                    java.util.Map.entry(158, "arch_prctl"),
                    java.util.Map.entry(202, "futex"),
                    java.util.Map.entry(228, "clock_gettime"),
                    java.util.Map.entry(231, "exit_group"),
                    java.util.Map.entry(318, "getrandom"));

    private static String locateOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, name);
            if (f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private void attachLinuxCgroup(Process process) {
        int pid = getProcessId(process);
        if (pid <= 0) {
            log.warn("KernelSandboxSubstrate: could not determine child pid for cgroup attach");
            return;
        }
        // cgroup v2 unified hierarchy. Production: configurable path + memory.max + cpu.max
        // (and the slice name). For the MVP we just write the defaults to a path the
        // Veto process can write to.
        String cgroupBase = "/sys/fs/cgroup";
        if (!new java.io.File(cgroupBase).isDirectory()) {
            log.debug(
                    "KernelSandboxSubstrate: cgroup v2 hierarchy not available at {}", cgroupBase);
            return;
        }
        // 1. Make sure the cgroup exists for this child (mkdir if not present).
        java.io.File childCgroup = new java.io.File(cgroupBase, "veto.slice/veto-" + pid);
        if (!childCgroup.exists() && !childCgroup.mkdirs()) {
            log.debug(
                    "KernelSandboxSubstrate: could not create cgroup {} — wall limited",
                    childCgroup);
            return;
        }
        // 2. Add the child to its cgroup.
        try {
            java.nio.file.Files.writeString(
                    childCgroup.toPath().resolve("cgroup.procs"), Integer.toString(pid));
        } catch (java.io.IOException e) {
            log.debug("KernelSandboxSubstrate: cgroup.procs write failed: {}", e.getMessage());
            return;
        }
        // 3. Apply default limits (memory.max = 512 MiB, cpu.max = 50% of one CPU).
        try {
            java.nio.file.Files.writeString(childCgroup.toPath().resolve("memory.max"), "512M");
            java.nio.file.Files.writeString(
                    childCgroup.toPath().resolve("cpu.max"), "50000 100000");
        } catch (java.io.IOException e) {
            log.debug("KernelSandboxSubstrate: cgroup limit write failed: {}", e.getMessage());
        }
        log.info(
                "KernelSandboxSubstrate: attached Linux cgroup wall to pid {} at {}",
                pid,
                childCgroup);
    }

    private void attachWindowsJobObject(Process process) {
        try {
            // 1. Create a Job Object with KILL_ON_JOB_CLOSE so the process is terminated
            //    if the parent (the Veto process) crashes.
            WinNT.HANDLE job = windowsKernel.CreateJobObjectW(null, null);
            if (job == null || job.getPointer() == null || job.getPointer().equals(Pointer.NULL)) {
                log.warn("KernelSandboxSubstrate: CreateJobObjectW failed");
                return;
            }
            // 2. Build a JOBOBJECT_EXTENDED_LIMIT_INFORMATION via JNA Structure (NOT a raw byte
            //    buffer). The previous implementation wrote BasicLimitFlags at byte offset 24 of
            //    a 144-byte buffer while passing
            // JobObjectInfoClass=JobObjectExtendedLimitInformation
            //    (infoClass 9). BasicLimitFlags actually lives at offset 8 of the inner
            //    JOBOBJECT_BASIC_LIMIT_INFORMATION struct; the wrong offset + wrong class meant
            //    the kernel silently ignored KILL_ON_JOB_CLOSE, defeating the "kill the child if
            //    Veto crashes" guarantee. JNA Structure with @FieldOrder avoids the manual
            //    offset arithmetic and matches the WinNT layout.
            JobObjectExtendedLimitInformation info = new JobObjectExtendedLimitInformation();
            info.BasicLimitInformation.LimitFlags =
                    JobObjectLimit.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            info.write();
            if (!windowsKernel.SetInformationJobObject(
                    job, JobObjectInfoClass.JobObjectExtendedLimitInformation, info, info.size())) {
                log.warn("KernelSandboxSubstrate: SetInformationJobObject failed");
                return;
            }
            // 3. AssignProcessToJobObject — pin the child to the Job.
            Kernel32 kernel32 = windowsStdKernel;
            int pid = getProcessId(process);
            if (pid <= 0) {
                log.warn("KernelSandboxSubstrate: could not determine child pid");
                return;
            }
            WinNT.HANDLE handle =
                    kernel32.OpenProcess(
                            0x1F0FFF, // PROCESS_ALL_ACCESS
                            false, pid);
            // Windows OpenProcess returns either a valid HANDLE, NULL (0) on some failures,
            // or INVALID_HANDLE_VALUE (0xFFFFFFFFFFFFFFFF, i.e. (HANDLE)-1) on others (e.g.
            // access denied, invalid parameter). Checking only Pointer.NULL would let
            // INVALID_HANDLE_VALUE slip through; AssignProcessToJobObject then fails with
            // ERROR_INVALID_HANDLE and the child is silently NOT pinned to the Job, defeating
            // KILL_ON_JOB_CLOSE. Catch all three failure sentinels.
            Pointer handlePtr = handle == null ? null : handle.getPointer();
            Pointer invalidHandleValue = Pointer.createConstant(-1L);
            if (handle == null
                    || handlePtr == null
                    || Pointer.NULL.equals(handlePtr)
                    || invalidHandleValue.equals(handlePtr)) {
                log.warn(
                        "KernelSandboxSubstrate: OpenProcess failed for pid {} (Win32 error={})",
                        pid,
                        kernel32.GetLastError());
                return;
            }
            if (!windowsKernel.AssignProcessToJobObject(job, handle)) {
                log.warn(
                        "KernelSandboxSubstrate: AssignProcessToJobObject failed (Win32 error={})",
                        kernel32.GetLastError());
                return;
            }
            log.info("KernelSandboxSubstrate: attached Windows Job Object to pid {}", pid);
        } catch (Throwable t) {
            log.warn("KernelSandboxSubstrate: Windows attach failed: {}", t.getMessage());
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
            log.debug("KernelSandboxSubstrate: process.pid() failed: {}", t.getMessage());
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
    }

    /** Minimal JNA interface for the Linux libc calls we need (extensible to libseccomp). */
    public interface LinuxLibc extends Library {
        int unshare(int flags);

        int prctl(int option, long arg2, long arg3, long arg4, long arg5);
    }

    // --- Windows job-object structs (JOBOBJECT_EXTENDED_LIMIT_INFORMATION, WinNT.h) ---

    /** Win32 {@code JOBOBJECT_BASIC_LIMIT_INFORMATION}. */
    public static class JobObjectBasicLimitInformation extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public long MinimumWorkingSetSize;
        public long MaximumWorkingSetSize;
        public int ActiveProcessLimit;
        public long Affinity;
        public int PriorityClass;
        public int SchedulingClass;

        @Override
        protected List<String> getFieldOrder() {
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
        protected List<String> getFieldOrder() {
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
        public JobObjectBasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public long ProcessMemoryLimit;
        public long JobMemoryLimit;
        public long PeakProcessMemoryUsed;
        public long PeakJobMemoryUsed;

        public JobObjectExtendedLimitInformation() {
            BasicLimitInformation = new JobObjectBasicLimitInformation();
            IoInfo = new IoCounters();
        }

        @Override
        protected List<String> getFieldOrder() {
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
        public static final int JobObjectExtendedLimitInformation = 9;

        private JobObjectInfoClass() {}
    }

    /** {@code JOBOBJECT_LIMIT} flags (subset). */
    public static final class JobObjectLimit {
        public static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;

        private JobObjectLimit() {}
    }
}
