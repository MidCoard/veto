package top.focess.veto.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Veto-owned macOS Seatbelt profile generator and launcher.
 *
 * <p>{@code sandbox-exec} is an Apple-provided compatibility interface, not the signed App Sandbox
 * entitlement API. Veto therefore probes it at runtime and fails closed when it is absent. The
 * profile is passed as one argv element and the approved command remains argv-only; no shell string
 * is introduced.
 */
final class MacOsSeatbeltSandbox {

    static final @NonNull Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");

    boolean isAvailable() {
        return Files.isRegularFile(SANDBOX_EXEC) && Files.isExecutable(SANDBOX_EXEC);
    }

    @NonNull List<@NonNull String> wrap(
            @NonNull List<@NonNull String> targetCommand, @NonNull SandboxProfile profile) {
        if (targetCommand.isEmpty()) {
            throw new IllegalArgumentException("targetCommand must not be empty");
        }
        if (!isAvailable()) {
            throw new IllegalStateException("/usr/bin/sandbox-exec is unavailable");
        }
        Path workspace;
        try {
            workspace = profile.workspaceRoot().toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "macOS sandbox workspace cannot be canonicalized: " + profile.workspaceRoot(),
                    e);
        }
        List<String> command = new ArrayList<>();
        command.add(SANDBOX_EXEC.toString());
        command.add("-p");
        command.add(profile(workspace, targetCommand.getFirst()));
        command.addAll(targetCommand);
        return List.copyOf(command);
    }

    /**
     * Build a default-deny profile: system/toolchain paths are read-only, the workspace is the only
     * writable subtree, external network and cross-sandbox process control remain denied.
     */
    static @NonNull String profile(@NonNull Path workspaceRoot) {
        return profile(workspaceRoot, "");
    }

    private static @NonNull String profile(
            @NonNull Path workspaceRoot, @NonNull String executable) {
        Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();
        String workspace = seatbeltString(normalizedWorkspace.toString());
        String executableRule = executableReadRule(executable);
        String protectedMetadataRules = protectedMetadataRules(normalizedWorkspace);
        return String.join(
                "\n",
                "(version 1)",
                "(deny default)",
                "",
                "; target process tree",
                "(allow process-exec)",
                "(allow process-fork)",
                "(allow process-info* (target same-sandbox))",
                "(allow signal (target same-sandbox))",
                "(allow mach-priv-task-port (target same-sandbox))",
                "",
                "; minimal runtime IPC",
                "(allow ipc-posix-sem)",
                "(allow ipc-posix-shm-read-data",
                "  ipc-posix-shm-write-create",
                "  ipc-posix-shm-write-unlink",
                "  (ipc-posix-name-regex #\"^/__KMP_REGISTERED_LIB_[0-9]+$\"))",
                "(allow user-preference-read)",
                "(allow distributed-notification-post)",
                "(allow mach-lookup",
                "  (global-name \"com.apple.FontObjectsServer\")",
                "  (global-name \"com.apple.fonts\")",
                "  (global-name \"com.apple.logd\")",
                "  (global-name \"com.apple.system.logger\")",
                "  (global-name \"com.apple.system.opendirectoryd.libinfo\")",
                "  (global-name \"com.apple.system.opendirectoryd.membership\")",
                "  (global-name \"com.apple.securityd.xpc\")",
                "  (global-name \"com.apple.SecurityServer\"))",
                "",
                "; read-only operating-system and toolchain roots",
                "(allow file-read-metadata (literal \"/\"))",
                "(allow file-read*",
                "  (subpath \"/System\")",
                "  (subpath \"/usr\")",
                "  (subpath \"/bin\")",
                "  (subpath \"/sbin\")",
                "  (subpath \"/Library\")",
                "  (subpath \"/Applications\")",
                "  (subpath \"/opt/homebrew\")",
                "  (subpath \"/private/etc\")",
                "  (subpath \"/private/var/db/timezone\")",
                "  (subpath \"/dev\")",
                "  (subpath \"" + workspace + "\"))",
                executableRule,
                "",
                "; standard character devices and PTY support",
                "(allow file-write-data",
                "  (require-all (path \"/dev/null\") (vnode-type CHARACTER-DEVICE)))",
                "(allow pseudo-tty)",
                "(allow file-read* file-write* file-ioctl (literal \"/dev/ptmx\"))",
                "(allow file-read* file-write*",
                "  (require-all",
                "    (regex #\"^/dev/ttys[0-9]+\")",
                "    (extension \"com.apple.sandbox.pty\")))",
                "(allow file-ioctl (regex #\"^/dev/ttys[0-9]+\"))",
                "",
                "; workspace is the only writable subtree",
                "(allow file-write* (subpath \"" + workspace + "\"))",
                protectedMetadataRules,
                "",
                "; bounded runtime discovery; network remains denied by default",
                "(allow sysctl-read",
                "  (sysctl-name \"hw.activecpu\")",
                "  (sysctl-name \"hw.logicalcpu\")",
                "  (sysctl-name \"hw.logicalcpu_max\")",
                "  (sysctl-name \"hw.machine\")",
                "  (sysctl-name \"hw.memsize\")",
                "  (sysctl-name \"hw.ncpu\")",
                "  (sysctl-name \"hw.pagesize\")",
                "  (sysctl-name \"hw.physicalcpu\")",
                "  (sysctl-name \"hw.physicalcpu_max\")",
                "  (sysctl-name-prefix \"hw.optional.arm.\")",
                "  (sysctl-name-prefix \"hw.optional.armv8_\")",
                "  (sysctl-name \"kern.argmax\")",
                "  (sysctl-name \"kern.hostname\")",
                "  (sysctl-name \"kern.osproductversion\")",
                "  (sysctl-name \"kern.osrelease\")",
                "  (sysctl-name \"kern.ostype\")",
                "  (sysctl-name \"kern.osversion\")",
                "  (sysctl-name \"kern.version\")",
                "  (sysctl-name \"vm.loadavg\"))",
                "; Java CPU detection is classified as a write by Seatbelt",
                "(allow sysctl-write (sysctl-name \"kern.grade_cputype\"))",
                "(allow system-socket",
                "  (require-all (socket-domain AF_SYSTEM) (socket-protocol 2)))",
                "");
    }

    private static @NonNull String protectedMetadataRules(@NonNull Path workspace) {
        List<String> rules = new ArrayList<>();
        rules.add("; agent-control metadata stays read-only inside a writable workspace");
        for (String name : List.of(".agents", ".codex")) {
            String protectedPath = seatbeltString(workspace.resolve(name).toString());
            rules.add("(deny file-write* (subpath \"" + protectedPath + "\"))");
        }
        return String.join("\n", rules);
    }

    private static @NonNull String executableReadRule(@NonNull String executable) {
        if (executable.isBlank()) {
            return "";
        }
        Path path = Path.of(executable);
        if (!path.isAbsolute()) {
            return "";
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path real = absolute;
        try {
            real = absolute.toRealPath();
        } catch (java.io.IOException ignored) {
            // The subsequent exec fails normally if the approved executable disappeared.
        }
        String absoluteValue = seatbeltString(absolute.toString());
        String realValue = seatbeltString(real.toString());
        if (absoluteValue.equals(realValue)) {
            return "(allow file-read* (literal \"" + absoluteValue + "\"))";
        }
        return "(allow file-read* (literal \""
                + absoluteValue
                + "\") (literal \""
                + realValue
                + "\"))";
    }

    private static @NonNull String seatbeltString(@NonNull String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
