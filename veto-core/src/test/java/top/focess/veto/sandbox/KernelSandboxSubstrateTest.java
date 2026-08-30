package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the kernel-sandbox substrate platform detection. */
class KernelSandboxSubstrateTest {

    @Test
    void kernelSandboxSubstrateConstructs() {
        // Should construct without throwing regardless of platform.
        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        assertNotNull(substrate);
    }

    @Test
    void isAvailableReflectsPlatform() {
        KernelSandboxSubstrate substrate = new KernelSandboxSubstrate();
        boolean available = substrate.isAvailable();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            assertTrue(available, "The mandatory Windows kernel32 bindings must load");
        } else {
            assertFalse(available, "No Linux hard wall is implemented yet");
        }
    }

    @Test
    void windowsCommandLinePreservesSpacesQuotesAndTrailingBackslashes() {
        assertEquals(
                "plain \"two words\" \"say\\\"hello\" \"C:\\path with space\\\\\"",
                SandboxBootstrap.windowsCommandLine(
                        List.of("plain", "two words", "say\"hello", "C:\\path with space\\")));
    }

    @Test
    void windowsJobLimitsReflectSandboxProfile() {
        SandboxProfile profile =
                new SandboxProfile(Path.of("workspace"), 768, 35, 12, Duration.ofSeconds(30));

        KernelSandboxSubstrate.JobObjectExtendedLimitInformation limits =
                KernelSandboxSubstrate.extendedLimits(profile);
        int flags = limits.BasicLimitInformation.LimitFlags;

        assertEquals(12, limits.BasicLimitInformation.ActiveProcessLimit);
        assertEquals(768L * 1_048_576L, limits.JobMemoryLimit.longValue());
        assertNotEquals(
                0,
                flags & KernelSandboxSubstrate.JobObjectLimit.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
        assertNotEquals(
                0, flags & KernelSandboxSubstrate.JobObjectLimit.JOB_OBJECT_LIMIT_ACTIVE_PROCESS);
        assertNotEquals(
                0, flags & KernelSandboxSubstrate.JobObjectLimit.JOB_OBJECT_LIMIT_JOB_MEMORY);
    }

    @Test
    void windowsCpuAndUiLimitsAreFailClosedDefaults() {
        SandboxProfile profile =
                new SandboxProfile(Path.of("workspace"), 512, 27, 8, Duration.ofSeconds(30));

        KernelSandboxSubstrate.JobObjectCpuRateControlInformation cpu =
                KernelSandboxSubstrate.cpuLimits(profile);
        KernelSandboxSubstrate.JobObjectBasicUiRestrictions ui = KernelSandboxSubstrate.uiLimits();

        assertEquals(2_700, cpu.CpuRate);
        assertEquals(
                KernelSandboxSubstrate.JobObjectCpuRateControl.JOB_OBJECT_CPU_RATE_CONTROL_ENABLE
                        | KernelSandboxSubstrate.JobObjectCpuRateControl
                                .JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP,
                cpu.ControlFlags);
        assertEquals(
                KernelSandboxSubstrate.JobObjectUiLimit.JOB_OBJECT_UILIMIT_ALL,
                ui.UIRestrictionsClass);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionSubstrateGatesTargetUntilJobAttachment(@TempDir @NonNull Path workspace) {
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile = new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30));
        SandboxHandle handle = substrate.provision(profile);

        CommandResult result =
                substrate.runCommands(
                        handle,
                        java.util.List.of(
                                new Command("cmd", java.util.List.of("/c", "echo", "sandbox-ok"))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(20));

        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("sandbox-ok"), result.stdout());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionSubstrateRunsWindowsCommandShimWithoutAUniversalShell(
            @TempDir @NonNull Path workspace) throws Exception {
        Files.writeString(workspace.resolve("probe.cmd"), "@echo off\r\necho command-shim-ok\r\n");
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile = new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30));
        SandboxHandle handle = substrate.provision(profile);

        CommandResult result =
                substrate.runCommands(
                        handle,
                        List.of(new Command("probe.cmd", List.of())),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(20));

        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("command-shim-ok"), result.stdout());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionTargetRunsInAppContainerWithReadAndNetworkIsolation(
            @TempDir @NonNull Path temporaryRoot) throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path outside = Files.createDirectory(temporaryRoot.resolve("outside"));
        Path insideTarget = workspace.resolve("inside.txt");
        Path outsideTarget = outside.resolve("outside.txt");
        Path secret = Files.writeString(outside.resolve("secret.txt"), "host-secret");
        Path probeRoot = installEscapeProbe(workspace);
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile = new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30));
        SandboxHandle handle = substrate.provision(profile);
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();

        CommandResult result =
                substrate.runCommands(
                        handle,
                        List.of(
                                new Command(
                                        java,
                                        List.of(
                                                "-Xms8m",
                                                "-Xmx64m",
                                                "-XX:MaxMetaspaceSize=64m",
                                                "-XX:ReservedCodeCacheSize=32m",
                                                "-XX:+UseSerialGC",
                                                "-cp",
                                                probeRoot.toString(),
                                                "top.focess.veto.sandbox.WindowsSandboxEscapeProbe",
                                                insideTarget.toString(),
                                                outsideTarget.toString(),
                                                secret.toString()))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(20));

        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("inside-write=allowed"), result.stdout());
        assertTrue(result.stdout().contains("outside-write=denied"), result.stdout());
        assertTrue(result.stdout().contains("outside-read=denied"), result.stdout());
        String sandboxTemp =
                workspace.resolve(".veto/sandbox-tmp").toAbsolutePath().normalize().toString();
        String normalizedOutput = result.stdout().toLowerCase(Locale.ROOT);
        assertTrue(
                normalizedOutput.contains("\\appdata\\local\\packages\\vetosandbox."),
                result.stdout());
        assertTrue(normalizedOutput.contains("\\ac\\temp"), result.stdout());
        assertTrue(result.stdout().contains("tmpdir=" + sandboxTemp), result.stdout());
        assertTrue(Files.exists(insideTarget));
        assertFalse(Files.exists(outsideTarget));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionWriteBoundaryAllowsWorkspaceAndDeniesSibling(
            @TempDir @NonNull Path temporaryRoot) throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path outside = Files.createDirectory(temporaryRoot.resolve("outside"));
        Path insideTarget = workspace.resolve("inside.txt");
        Path outsideTarget = outside.resolve("outside.txt");
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile = new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30));
        SandboxHandle handle = substrate.provision(profile);
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Path probeRoot = installEscapeProbe(workspace);

        CommandResult result =
                substrate.runCommands(
                        handle,
                        List.of(
                                new Command(
                                        java,
                                        List.of(
                                                "-Xms8m",
                                                "-Xmx64m",
                                                "-XX:MaxMetaspaceSize=64m",
                                                "-XX:ReservedCodeCacheSize=32m",
                                                "-XX:+UseSerialGC",
                                                "-cp",
                                                probeRoot.toString(),
                                                "top.focess.veto.sandbox.WindowsSandboxEscapeProbe",
                                                insideTarget.toString(),
                                                outsideTarget.toString(),
                                                outside.resolve("missing-secret.txt").toString()))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(20));

        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("inside-write=allowed"), result.stdout());
        assertTrue(result.stdout().contains("outside-write=denied"), result.stdout());
        assertTrue(Files.exists(insideTarget));
        assertFalse(Files.exists(outsideTarget));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionAppContainerDeniesExistingProtectedPathInsideWorkspace(
            @TempDir @NonNull Path temporaryRoot) throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path protectedFile = Files.writeString(workspace.resolve(".env"), "VETO_SECRET=value");
        Path insideTarget = workspace.resolve("inside.txt");
        Path outsideTarget = temporaryRoot.resolve("outside.txt");
        Path probeRoot = installEscapeProbe(workspace);
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile =
                new SandboxProfile(
                        workspace,
                        512,
                        100,
                        8,
                        Duration.ofSeconds(30),
                        java.util.Set.of(protectedFile));
        SandboxHandle handle = substrate.provision(profile);
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();

        CommandResult result =
                substrate.runCommands(
                        handle,
                        List.of(
                                new Command(
                                        java,
                                        List.of(
                                                "-Xms8m",
                                                "-Xmx64m",
                                                "-XX:MaxMetaspaceSize=64m",
                                                "-XX:ReservedCodeCacheSize=32m",
                                                "-XX:+UseSerialGC",
                                                "-cp",
                                                probeRoot.toString(),
                                                "top.focess.veto.sandbox.WindowsSandboxEscapeProbe",
                                                insideTarget.toString(),
                                                outsideTarget.toString(),
                                                protectedFile.toString()))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(20));

        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("inside-write=allowed"), result.stdout());
        assertTrue(result.stdout().contains("outside-read=denied"), result.stdout());
        assertTrue(Files.readString(protectedFile).contains("VETO_SECRET=value"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionAppContainerMasksMissingProtectedFileAndCleansEmptyMask(
            @TempDir @NonNull Path temporaryRoot) throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path protectedFile = workspace.resolve(".env");
        Path insideTarget = workspace.resolve("inside.txt");
        Path outsideTarget = temporaryRoot.resolve("outside.txt");
        Path probeRoot = installEscapeProbe(workspace);
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile =
                new SandboxProfile(
                        workspace,
                        512,
                        100,
                        8,
                        Duration.ofSeconds(30),
                        java.util.Set.of(protectedFile));
        SandboxHandle handle = substrate.provision(profile);
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        try {
            assertTrue(Files.exists(protectedFile), "Veto owns a temporary creation mask");
            CommandResult result =
                    substrate.runCommands(
                            handle,
                            List.of(
                                    new Command(
                                            java,
                                            List.of(
                                                    "-Xms8m",
                                                    "-Xmx64m",
                                                    "-XX:MaxMetaspaceSize=64m",
                                                    "-XX:ReservedCodeCacheSize=32m",
                                                    "-XX:+UseSerialGC",
                                                    "-cp",
                                                    probeRoot.toString(),
                                                    "top.focess.veto.sandbox.WindowsSandboxEscapeProbe",
                                                    insideTarget.toString(),
                                                    outsideTarget.toString(),
                                                    protectedFile.toString()))),
                            Path.of("."),
                            ChainMode.STOP_ON_FAILURE,
                            Duration.ofSeconds(20));

            assertEquals(
                    0,
                    result.exitCode(),
                    "stdout=" + result.stdout() + "; stderr=" + result.stderr());
            assertTrue(result.stdout().contains("outside-read=denied"), result.stdout());
        } finally {
            substrate.deprovision(handle);
        }
        assertFalse(Files.exists(protectedFile), "unchanged empty creation mask is removed");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void protectedCreationMaskNeverDeletesHostContent(@TempDir @NonNull Path temporaryRoot)
            throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path protectedFile = workspace.resolve(".env");
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile =
                new SandboxProfile(
                        workspace,
                        512,
                        100,
                        8,
                        Duration.ofSeconds(30),
                        java.util.Set.of(protectedFile));
        SandboxHandle handle = substrate.provision(profile);

        Files.writeString(protectedFile, "HOST_SECRET=value");
        substrate.deprovision(handle);

        assertEquals("HOST_SECRET=value", Files.readString(protectedFile));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionAppContainerDeniesLoopbackNetwork(@TempDir @NonNull Path workspace)
            throws Exception {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/probe",
                exchange -> {
                    byte[] body =
                            "network-escape".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(body);
                    }
                });
        server.start();
        try {
            KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
            ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
            SandboxProfile profile =
                    new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30));
            SandboxHandle handle = substrate.provision(profile);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/probe";

            CommandResult result =
                    substrate.runCommands(
                            handle,
                            List.of(
                                    new Command(
                                            "curl",
                                            List.of(
                                                    "--max-time",
                                                    "2",
                                                    "--silent",
                                                    "--show-error",
                                                    url))),
                            Path.of("."),
                            ChainMode.STOP_ON_FAILURE,
                            Duration.ofSeconds(10));

            assertNotEquals(
                    0,
                    result.exitCode(),
                    "stdout=" + result.stdout() + "; stderr=" + result.stderr());
            assertFalse(result.stdout().contains("network-escape"), result.stdout());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionAppContainerLaunchesWithPermitScopedNetworkCapabilities(
            @TempDir @NonNull Path workspace) {
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile =
                new SandboxProfile(
                        workspace,
                        512,
                        100,
                        8,
                        Duration.ofSeconds(30),
                        true,
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of());
        SandboxHandle handle = substrate.provision(profile);

        CommandResult result =
                substrate.runCommands(
                        handle,
                        List.of(new Command("cmd", List.of("/c", "echo", "network-capability-ok"))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(20));

        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("network-capability-ok"), result.stdout());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionPipelineEstablishesEveryJobBeforeTargetsRun(@TempDir @NonNull Path workspace) {
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile = new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30));
        SandboxHandle handle = substrate.provision(profile);

        CommandResult result =
                substrate.runCommands(
                        handle,
                        java.util.List.of(
                                new Command(
                                        "cmd", java.util.List.of("/c", "echo", "sandbox-pipeline")),
                                new Command("findstr", java.util.List.of("sandbox-pipeline"))),
                        Path.of("."),
                        ChainMode.PIPE,
                        Duration.ofSeconds(20));

        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("sandbox-pipeline"), result.stdout());
        assertEquals(java.util.List.of(0, 0), result.perCommand());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionBackgroundLaunchUsesTheSameGate(@TempDir @NonNull Path workspace)
            throws Exception {
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxProfile profile = new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30));
        SandboxHandle handle = substrate.provision(profile);

        Process process =
                substrate.startBackground(
                        handle,
                        new Command("cmd", java.util.List.of("/c", "echo", "background-ok")),
                        Path.of("."));

        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }

    private static @NonNull Path installEscapeProbe(@NonNull Path workspace) throws IOException {
        String resource = "top/focess/veto/sandbox/WindowsSandboxEscapeProbe.class";
        Path root = Files.createDirectories(workspace.resolve("probe-classes"));
        Path packageDirectory = Files.createDirectories(root.resolve("top/focess/veto/sandbox"));
        Path target = packageDirectory.resolve("WindowsSandboxEscapeProbe.class");
        InputStream resourceStream = ClassLoader.getSystemResourceAsStream(resource);
        if (resourceStream == null) {
            throw new IOException("Compiled Windows escape probe is unavailable");
        }
        try (InputStream input = resourceStream) {
            Files.copy(input, target);
        }
        return root;
    }
}
