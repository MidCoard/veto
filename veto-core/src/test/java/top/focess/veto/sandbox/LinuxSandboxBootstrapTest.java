package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class LinuxSandboxBootstrapTest {

    private static final int AUDIT_ARCH_X86_64 = 0xC000003E;
    private static final int AUDIT_ARCH_AARCH64 = 0xC00000B7;
    private static final int AF_UNIX = 1;
    private static final int AF_INET = 2;
    private static final int SECCOMP_RET_KILL_PROCESS = 0x80000000;
    private static final int SECCOMP_RET_ERRNO_EPERM = 0x00050001;
    private static final int SECCOMP_RET_ALLOW = 0x7fff0000;

    @Test
    void x86ProgramAllowsOrdinarySyscallsAndUnixSocketCreationButDeniesNetwork() {
        List<LinuxSandboxBootstrap.SockFilter> program =
                LinuxSandboxBootstrap.networkDenyProgram(
                        LinuxSandboxBootstrap.LinuxArchitecture.X86_64);

        assertEquals(SECCOMP_RET_ALLOW, evaluate(program, AUDIT_ARCH_X86_64, 1, 0));
        assertEquals(SECCOMP_RET_ALLOW, evaluate(program, AUDIT_ARCH_X86_64, 41, AF_UNIX));
        assertEquals(SECCOMP_RET_ERRNO_EPERM, evaluate(program, AUDIT_ARCH_X86_64, 41, AF_INET));
        assertEquals(SECCOMP_RET_ALLOW, evaluate(program, AUDIT_ARCH_X86_64, 53, AF_UNIX));
        assertEquals(SECCOMP_RET_ERRNO_EPERM, evaluate(program, AUDIT_ARCH_X86_64, 53, AF_INET));
        assertEquals(SECCOMP_RET_ERRNO_EPERM, evaluate(program, AUDIT_ARCH_X86_64, 42, 0));
        assertEquals(SECCOMP_RET_ERRNO_EPERM, evaluate(program, AUDIT_ARCH_X86_64, 101, 0));
        assertEquals(SECCOMP_RET_KILL_PROCESS, evaluate(program, AUDIT_ARCH_AARCH64, 1, 0));
    }

    @Test
    void armProgramUsesArmSyscallNumbers() {
        List<LinuxSandboxBootstrap.SockFilter> program =
                LinuxSandboxBootstrap.networkDenyProgram(
                        LinuxSandboxBootstrap.LinuxArchitecture.AARCH64);

        assertEquals(SECCOMP_RET_ALLOW, evaluate(program, AUDIT_ARCH_AARCH64, 64, 0));
        assertEquals(SECCOMP_RET_ALLOW, evaluate(program, AUDIT_ARCH_AARCH64, 198, AF_UNIX));
        assertEquals(SECCOMP_RET_ERRNO_EPERM, evaluate(program, AUDIT_ARCH_AARCH64, 198, AF_INET));
        assertEquals(SECCOMP_RET_ALLOW, evaluate(program, AUDIT_ARCH_AARCH64, 199, AF_UNIX));
        assertEquals(SECCOMP_RET_ERRNO_EPERM, evaluate(program, AUDIT_ARCH_AARCH64, 199, AF_INET));
        assertEquals(SECCOMP_RET_ERRNO_EPERM, evaluate(program, AUDIT_ARCH_AARCH64, 203, 0));
        assertEquals(SECCOMP_RET_ERRNO_EPERM, evaluate(program, AUDIT_ARCH_AARCH64, 117, 0));
    }

    @Test
    void sandboxBootstrapRecognizesLinuxInnerStage() {
        assertTrue(
                SandboxBootstrap.isInvocation(
                        new String[] {SandboxBootstrap.LINUX_CHILD_MARKER, "--", "echo"}));
    }

    private static int evaluate(
            @NonNull List<LinuxSandboxBootstrap.@NonNull SockFilter> program,
            int architecture,
            int syscall,
            int arg0) {
        int accumulator = 0;
        for (int pc = 0; pc < program.size(); ) {
            LinuxSandboxBootstrap.SockFilter instruction = program.get(pc);
            int code = Short.toUnsignedInt(instruction.code);
            if (code == 0x20) {
                accumulator =
                        switch (instruction.k) {
                            case 0 -> syscall;
                            case 4 -> architecture;
                            case 16 -> arg0;
                            default ->
                                    throw new AssertionError(
                                            "Unexpected seccomp_data offset " + instruction.k);
                        };
                pc++;
            } else if (code == 0x15) {
                int jump =
                        accumulator == instruction.k
                                ? Byte.toUnsignedInt(instruction.jt)
                                : Byte.toUnsignedInt(instruction.jf);
                pc += jump + 1;
            } else if (code == 0x06) {
                return instruction.k;
            } else {
                throw new AssertionError("Unexpected BPF instruction " + code);
            }
        }
        throw new AssertionError("Seccomp program did not return");
    }
}
