package top.focess.veto.sandbox;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDocs;

/** Inner Linux stage that applies {@code no_new_privs} and seccomp before the target starts. */
final class LinuxSandboxBootstrap {

    private static final int PR_SET_NO_NEW_PRIVS = 38;
    private static final int PR_SET_SECCOMP = 22;
    private static final int SECCOMP_MODE_FILTER = 2;
    private static final int EPERM = 1;

    private static final short BPF_LD_W_ABS = 0x20;
    private static final short BPF_JMP_JEQ_K = 0x15;
    private static final short BPF_RET_K = 0x06;
    private static final int SECCOMP_RET_KILL_PROCESS = 0x80000000;
    private static final int SECCOMP_RET_ERRNO = 0x00050000;
    private static final int SECCOMP_RET_ALLOW = 0x7fff0000;

    private static final int SECCOMP_DATA_NR_OFFSET = 0;
    private static final int SECCOMP_DATA_ARCH_OFFSET = 4;
    private static final int SECCOMP_DATA_ARG0_OFFSET = 16;
    private static final int AF_UNIX = 1;

    private LinuxSandboxBootstrap() {}

    static int run(@NonNull List<@NonNull String> target) {
        if (target.isEmpty()) {
            System.err.println("Linux sandbox target is missing");
            return 125;
        }
        try {
            installSeccomp();
        } catch (RuntimeException e) {
            System.err.println("Linux seccomp initialization failed: " + e.getMessage());
            return 125;
        }
        ProcessBuilder builder = new ProcessBuilder(target);
        builder.inheritIO();
        try {
            Process process = builder.start();
            return process.waitFor();
        } catch (IOException e) {
            System.err.println("Linux sandbox target could not start: " + e.getMessage());
            return 126;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    private static void installSeccomp() {
        LinuxArchitecture architecture = LinuxArchitecture.current();
        List<SockFilter> instructions = networkDenyProgram(architecture);
        SockFilter first = new SockFilter();
        SockFilter[] filters = (SockFilter[]) first.toArray(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            SockFilter source = instructions.get(i);
            filters[i].code = source.code;
            filters[i].jt = source.jt;
            filters[i].jf = source.jf;
            filters[i].k = source.k;
            filters[i].write();
        }
        SockFprog program = new SockFprog();
        program.len = (short) filters.length;
        program.filter = filters[0].getPointer();
        program.write();

        LinuxLibC libc = Native.load("c", ToolDocs.nonNullClass(LinuxLibC.class));
        if (libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
            throw new IllegalStateException(
                    "prctl(PR_SET_NO_NEW_PRIVS) failed (errno=" + Native.getLastError() + ")");
        }
        if (libc.prctl(
                        PR_SET_SECCOMP,
                        SECCOMP_MODE_FILTER,
                        Pointer.nativeValue(program.getPointer()),
                        0,
                        0)
                != 0) {
            throw new IllegalStateException(
                    "prctl(PR_SET_SECCOMP) failed (errno=" + Native.getLastError() + ")");
        }
    }

    static @NonNull List<@NonNull SockFilter> networkDenyProgram(
            @NonNull LinuxArchitecture architecture) {
        List<SockFilter> program = new ArrayList<>();
        program.add(load(SECCOMP_DATA_ARCH_OFFSET));
        program.add(equal(architecture.auditArchitecture, 1, 0));
        program.add(ret(SECCOMP_RET_KILL_PROCESS));
        program.add(load(SECCOMP_DATA_NR_OFFSET));

        program.add(equal(architecture.socket, 0, 3));
        program.add(load(SECCOMP_DATA_ARG0_OFFSET));
        program.add(equal(AF_UNIX, 1, 0));
        program.add(ret(SECCOMP_RET_ERRNO | EPERM));
        program.add(load(SECCOMP_DATA_NR_OFFSET));

        program.add(equal(architecture.socketpair, 0, 3));
        program.add(load(SECCOMP_DATA_ARG0_OFFSET));
        program.add(equal(AF_UNIX, 1, 0));
        program.add(ret(SECCOMP_RET_ERRNO | EPERM));
        program.add(load(SECCOMP_DATA_NR_OFFSET));

        for (int syscall : architecture.deniedSyscalls) {
            program.add(equal(syscall, 0, 1));
            program.add(ret(SECCOMP_RET_ERRNO | EPERM));
        }
        program.add(ret(SECCOMP_RET_ALLOW));
        return List.copyOf(program);
    }

    private static @NonNull SockFilter load(int offset) {
        return new SockFilter(BPF_LD_W_ABS, 0, 0, offset);
    }

    private static @NonNull SockFilter equal(int value, int onTrue, int onFalse) {
        return new SockFilter(BPF_JMP_JEQ_K, onTrue, onFalse, value);
    }

    private static @NonNull SockFilter ret(int value) {
        return new SockFilter(BPF_RET_K, 0, 0, value);
    }

    enum LinuxArchitecture {
        X86_64(
                0xC000003E,
                41,
                53,
                42,
                43,
                44,
                48,
                49,
                50,
                51,
                52,
                54,
                55,
                101,
                288,
                299,
                307,
                310,
                311,
                425,
                426,
                427),
        AARCH64(
                0xC00000B7,
                198,
                199,
                203,
                202,
                206,
                210,
                200,
                201,
                204,
                205,
                209,
                208,
                117,
                242,
                243,
                269,
                270,
                271,
                425,
                426,
                427);

        private final int auditArchitecture;
        private final int socket;
        private final int socketpair;
        private final int @NonNull [] deniedSyscalls;

        LinuxArchitecture(
                int auditArchitecture,
                int socket,
                int socketpair,
                int @NonNull ... deniedSyscalls) {
            this.auditArchitecture = auditArchitecture;
            this.socket = socket;
            this.socketpair = socketpair;
            this.deniedSyscalls = Arrays.copyOf(deniedSyscalls, deniedSyscalls.length);
        }

        static @NonNull LinuxArchitecture current() {
            String architecture = System.getProperty("os.arch", "").toLowerCase();
            return switch (architecture) {
                case "amd64", "x86_64" -> X86_64;
                case "aarch64", "arm64" -> AARCH64;
                default ->
                        throw new IllegalStateException(
                                "Unsupported Linux seccomp architecture: " + architecture);
            };
        }
    }

    @Structure.FieldOrder({"code", "jt", "jf", "k"})
    public static class SockFilter extends Structure {
        public short code;
        public byte jt;
        public byte jf;
        public int k;

        public SockFilter() {}

        SockFilter(short code, int jt, int jf, int k) {
            this.code = code;
            this.jt = (byte) jt;
            this.jf = (byte) jf;
            this.k = k;
        }
    }

    @Structure.FieldOrder({"len", "filter"})
    public static class SockFprog extends Structure {
        public short len;
        public Pointer filter;
    }

    interface LinuxLibC extends Library {
        int prctl(int option, long arg2, long arg3, long arg4, long arg5);
    }
}
