package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxBubblewrapSandboxTest {

    @Test
    void commandUsesReadOnlyRootNamespacesWorkspaceBindAndInnerSeccomp(@TempDir @NonNull Path root)
            throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path cwd = Files.createDirectories(workspace.resolve("nested"));
        Files.createDirectory(workspace.resolve(".git"));
        Path launcher = currentJavaExecutable();
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox(launcher);

        List<String> command =
                sandbox.wrap(
                        List.of("/usr/bin/printf", "ok"),
                        new SandboxProfile(workspace, 512, 100, 16, Duration.ofSeconds(30)),
                        cwd);

        assertEquals(launcher.toRealPath().toString(), command.getFirst());
        assertContainsSequence(command, "--ro-bind", "/", "/");
        assertContainsSequence(
                command,
                "--bind",
                workspace.toRealPath().toString(),
                workspace.toRealPath().toString());
        assertContainsSequence(
                command,
                "--ro-bind",
                workspace.resolve(".git").toRealPath().toString(),
                workspace.resolve(".git").toRealPath().toString());
        assertContainsSequence(command, "--chdir", cwd.toRealPath().toString());
        assertTrue(command.contains("--unshare-user"));
        assertTrue(command.contains("--unshare-pid"));
        assertTrue(command.contains("--unshare-ipc"));
        assertTrue(command.contains("--unshare-net"));
        assertContainsSequence(command, "--cap-drop", "ALL");
        assertTrue(command.contains(SandboxBootstrap.LINUX_CHILD_MARKER));
        assertContainsSequence(command, "/usr/bin/printf", "ok");
    }

    @Test
    void cwdOutsideWorkspaceFailsBeforeBubblewrapStarts(@TempDir @NonNull Path root)
            throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox(currentJavaExecutable());

        assertThrows(
                SecurityException.class,
                () ->
                        sandbox.wrap(
                                List.of("echo", "no"),
                                new SandboxProfile(workspace, 512, 100, 16, Duration.ofSeconds(30)),
                                outside));
    }

    @Test
    void configuredLauncherInsideWorkspaceIsRejected(@TempDir @NonNull Path workspace)
            throws Exception {
        Path launcher = Files.createFile(workspace.resolve("bwrap"));
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox(launcher);

        assertThrows(
                IllegalStateException.class,
                () ->
                        sandbox.wrap(
                                List.of("echo"),
                                new SandboxProfile(workspace, 512, 100, 16, Duration.ofSeconds(30)),
                                workspace));
    }

    private static @NonNull Path currentJavaExecutable() {
        String executable =
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe"
                        : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static void assertContainsSequence(
            @NonNull List<@NonNull String> actual, String @NonNull ... expected) {
        for (int start = 0; start <= actual.size() - expected.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < expected.length; offset++) {
                if (!expected[offset].equals(actual.get(start + offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return;
            }
        }
        throw new AssertionError("Missing sequence " + List.of(expected) + " in " + actual);
    }
}
