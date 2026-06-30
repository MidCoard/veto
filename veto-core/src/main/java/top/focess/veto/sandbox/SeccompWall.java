package top.focess.veto.sandbox;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Part 5 §4.1 seccomp(2) syscall-allowlist <b>detector + spec</b>. Probes whether libseccomp is
 * loadable and exposes the baseline syscall allowlist a wrapper would apply — it does <b>not</b>
 * apply a filter itself.
 *
 * <p><b>Why not self-apply:</b> seccomp filtering is per-process and must be loaded into the
 * <i>child</i> between {@code fork()} and {@code execve()}. Java's {@link ProcessBuilder} exposes
 * no such hook, and loading a filter in this (parent) JVM would sandbox the <i>Veto process
 * itself</i>. The prior version called {@code seccomp_load} in its constructor — a latent footgun
 * that would have restricted the host if its (broken) reflection had ever resolved. Application
 * therefore requires a wrapper that execs the command with the filter loaded (e.g. a small native
 * helper or {@code systemd-run SystemCallFilter=}); that is the documented hard gap, tracked
 * separately. This class is the sound detector + the allowlist spec for such a wrapper.
 *
 * <p>The MVP path is reflection/JNA-free at runtime: {@link #isEnabled()} returns {@code false}
 * wherever libseccomp is absent (the common case on Windows / dev machines), and {@link
 * KernelSandboxSubstrate} falls back to constrained-subprocess (no syscall wall).
 */
@Component
public class SeccompWall {

    private static final Logger log = LoggerFactory.getLogger(SeccompWall.class);

    /** The loaded libseccomp binding, or {@code null} when libseccomp is unavailable. */
    private final @NonNull LinuxLibSeccomp lib;

    public SeccompWall() {
        LinuxLibSeccomp loaded = null;
        try {
            loaded = Native.load("seccomp", LinuxLibSeccomp.class);
            log.info(
                    "SeccompWall: libseccomp loadable; syscall wall available (applied via a wrapper)");
        } catch (Throwable t) {
            // libseccomp not on the system library path (Windows / dev) — degrade to disabled.
            log.info("SeccompWall: libseccomp not on classpath; syscall wall disabled");
        }
        this.lib = loaded;
    }

    /**
     * Whether libseccomp is loadable on this host (the syscall wall can be applied by a wrapper).
     */
    public boolean isEnabled() {
        return lib != null;
    }

    /**
     * The baseline syscall allowlist (x86_64 numbers) a wrapper would {@code seccomp_rule_add}
     * before exec'ing the sandboxed command. Read, write, open, close, mmap, brk, nanosleep,
     * getpid, exit, exit_group, arch_prctl, futex, clock_gettime, getrandom. Production would query
     * {@code uname(2)} and use the right architecture's table.
     */
    public List<Integer> baselineSyscalls() {
        return List.of(
                0, // read
                1, // write
                2, // open
                3, // close
                9, // mmap
                12, // brk
                35, // nanosleep
                39, // getpid
                60, // exit
                231, // exit_group
                158, // arch_prctl
                202, // futex
                228, // clock_gettime
                318 // getrandom
                );
    }

    /**
     * Minimal JNA binding for libseccomp. Bound lazily by JNA on first call; {@link Native#load}
     * only verifies the library is present at construction. The methods are retained for a future
     * wrapper; this class never calls {@code seccomp_load} (it would sandbox the host JVM).
     */
    public interface LinuxLibSeccomp extends Library {
        /**
         * {@code seccomp_init(SCMP_ACT_KILL)} — create a filter context that kills on violation.
         */
        Pointer seccomp_init(int action);

        int seccomp_rule_add(Pointer ctx, int action, int syscall, int argCount);

        int seccomp_load(Pointer ctx);

        void seccomp_release(Pointer ctx);
    }
}
