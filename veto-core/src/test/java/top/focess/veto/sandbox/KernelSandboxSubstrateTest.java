package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.ptr.PointerByReference;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;

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
    @EnabledOnOs(OS.WINDOWS)
    void windowsBootstrapPreservesActualChildArguments(@TempDir @NonNull Path temp)
            throws Exception {
        Path source = temp.resolve("ArgvProbe.java");
        Files.writeString(
                source,
                "class ArgvProbe { public static void main(String[] args) {"
                        + " System.out.print(java.util.Arrays.toString(args)); } }");
        List<String> arguments =
                List.of(
                        "plain",
                        "",
                        "two words",
                        "say\"hello",
                        "C:\\path with space\\",
                        "[Console]::Out.WriteLine(\"QUOTE_DOUBLE\")");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        command.add("-Djava.io.tmpdir=" + temp);
        command.add(source.toString());
        command.addAll(SandboxBootstrap.windowsBootstrapArguments(arguments));
        Process child =
                new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            String output =
                    new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertEquals(arguments.toString(), output);
        } finally {
            if (child.isAlive()) {
                child.destroy();
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void productionSubstratePreservesPowerShellDoubleQuotes(@TempDir @NonNull Path workspace)
            throws Exception {
        ConstrainedSubprocessSubstrate substrate =
                new ConstrainedSubprocessSubstrate(new KernelSandboxSubstrate());
        SandboxHandle handle =
                substrate.provision(
                        new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30)));
        CommandResult result =
                substrate.runCommands(
                        handle,
                        List.of(
                                new Command(
                                        "powershell.exe",
                                        List.of(
                                                "-NoProfile",
                                                "-NonInteractive",
                                                "-Command",
                                                "[Console]::Out.WriteLine(\"QUOTE_OUT\");"
                                                        + " [Console]::Error.WriteLine(\"QUOTE_ERR\")"))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(20));
        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("QUOTE_OUT"), result.stdout());
        assertTrue(result.stderr().contains("QUOTE_ERR"), result.stderr());
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
                        List.of(new Command("cmd", List.of("/c", "echo", "sandbox-ok"))),
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
    void foreignWorkspaceExclusionsDoNotDenyTheirOwners(@TempDir @NonNull Path temporaryRoot)
            throws Exception {
        Path alice = Files.createDirectory(temporaryRoot.resolve("alice"));
        Path bob = Files.createDirectory(temporaryRoot.resolve("bob"));
        // Suppress broad package and stale requester allows while preserving the rightful owner.
        allowApplicationPackages(alice);
        allowApplicationPackages(bob);
        Path aliceSecret = Files.writeString(alice.resolve("secret.txt"), "alice-secret");
        Path bobCache = Files.createDirectory(bob.resolve("cache"));
        allowApplicationPackages(bobCache); // explicit child allow, not just inherited root allow
        Path bobSecret = Files.writeString(bobCache.resolve("secret.txt"), "bob-secret");
        Path aliceProbe = installEscapeProbe(alice);
        Path bobProbe = installEscapeProbe(bob);
        Path javaHome = Path.of(System.getProperty("java.home"));
        ConstrainedSubprocessSubstrate substrate =
                new ConstrainedSubprocessSubstrate(new KernelSandboxSubstrate());
        SandboxProfile aliceProfile =
                new SandboxProfile(
                        alice,
                        512,
                        100,
                        8,
                        Duration.ofSeconds(30),
                        true,
                        Set.of(),
                        Set.of(javaHome),
                        Set.of(bobCache),
                        Set.of(bob));
        SandboxProfile bobProfile =
                new SandboxProfile(
                        bob,
                        512,
                        100,
                        8,
                        Duration.ofSeconds(30),
                        true,
                        Set.of(),
                        Set.of(javaHome),
                        Set.of(),
                        Set.of(alice));
        SandboxProfile unclaimedAliceProfile =
                new SandboxProfile(
                        alice,
                        512,
                        100,
                        8,
                        Duration.ofSeconds(30),
                        true,
                        Set.of(),
                        Set.of(javaHome),
                        Set.of());
        SandboxProfile overlappingAliceProfile =
                new SandboxProfile(
                        alice,
                        512,
                        100,
                        8,
                        Duration.ofSeconds(30),
                        true,
                        Set.of(),
                        Set.of(javaHome),
                        Set.of(temporaryRoot),
                        Set.of(bobCache));
        SandboxHandle priorAlice = substrate.provision(unclaimedAliceProfile);
        try {
            allowSandboxIdentity(bobCache, alice); // simulate a prior explicit compatibility grant
        } finally {
            substrate.deprovision(priorAlice);
        }
        SandboxHandle bobHandle = substrate.provision(bobProfile);
        try {
            var originalBobAcl = nativeAclSnapshot(bobCache);
            SandboxHandle aliceHandle = substrate.provision(aliceProfile);
            SandboxHandle secondAliceHandle = null;
            boolean firstAliceReleased = false;
            try {
                secondAliceHandle = substrate.provision(overlappingAliceProfile);
                // A later rightful-owner invocation must not reinherit the package allows removed
                // by Alice's active projection.
                SandboxHandle laterBob = substrate.provision(bobProfile);
                try {
                    assertWorkspaceProbe(
                            substrate,
                            laterBob,
                            bobProbe,
                            bob.resolve("inside.txt"),
                            alice.resolve("outside.txt"),
                            bobSecret,
                            "allowed");
                } finally {
                    substrate.deprovision(laterBob);
                }
                assertThrows(
                        SecurityException.class,
                        () ->
                                new WindowsWorkspaceSecurity()
                                        .provisionExecutable(bobSecret, aliceProfile));
                assertWorkspaceProbe(
                        substrate,
                        aliceHandle,
                        aliceProbe,
                        alice.resolve("inside.txt"),
                        bob.resolve("outside.txt"),
                        bobSecret,
                        "denied");
                assertWorkspaceProbe(
                        substrate,
                        bobHandle,
                        bobProbe,
                        bob.resolve("inside.txt"),
                        alice.resolve("outside.txt"),
                        aliceSecret,
                        "denied");
                assertWorkspaceProbe(
                        substrate,
                        aliceHandle,
                        aliceProbe,
                        alice.resolve("inside.txt"),
                        bob.resolve("outside.txt"),
                        aliceSecret,
                        "allowed");
                assertWorkspaceProbe(
                        substrate,
                        bobHandle,
                        bobProbe,
                        bob.resolve("inside.txt"),
                        alice.resolve("outside.txt"),
                        bobSecret,
                        "allowed");
                substrate.deprovision(aliceHandle);
                firstAliceReleased = true;
                assertWorkspaceProbe(
                        substrate,
                        secondAliceHandle,
                        aliceProbe,
                        alice.resolve("inside.txt"),
                        bobCache.resolve("outside.txt"),
                        bobSecret,
                        "denied");
            } finally {
                try {
                    if (secondAliceHandle != null) substrate.deprovision(secondAliceHandle);
                } finally {
                    if (!firstAliceReleased) substrate.deprovision(aliceHandle);
                }
            }
            var restoredBobAcl = nativeAclSnapshot(bobCache);
            assertEquals(
                    originalBobAcl.getValue(),
                    restoredBobAcl.getValue(),
                    "Retirement must restore exact ACE bytes, including inherited flags and ordering");
            // Local SetFileSecurity deliberately avoids descendant propagation and clears the
            // historical AUTO_INHERITED marker. Every other control bit, especially protection,
            // must survive. Inherited ACE flags themselves are compared exactly above.
            assertEquals(originalBobAcl.getKey() & ~0x400, restoredBobAcl.getKey() & ~0x400);
            // Foreign masks must not leave a broad denial after the requesting process ends.
            assertWorkspaceProbe(
                    substrate,
                    bobHandle,
                    bobProbe,
                    bob.resolve("inside.txt"),
                    alice.resolve("outside.txt"),
                    bobSecret,
                    "allowed");
            SandboxHandle unclaimedAlice = substrate.provision(unclaimedAliceProfile);
            try {
                assertWorkspaceProbe(
                        substrate,
                        unclaimedAlice,
                        aliceProbe,
                        alice.resolve("inside.txt"),
                        temporaryRoot.resolve("unavailable.txt"),
                        bobSecret,
                        "allowed");
            } finally {
                substrate.deprovision(unclaimedAlice);
            }
        } finally {
            substrate.deprovision(bobHandle);
        }
        assertEquals("alice-secret", Files.readString(aliceSecret));
        assertEquals("bob-secret", Files.readString(bobSecret));
        assertFalse(Files.exists(alice.resolve("outside.txt")));
        assertFalse(Files.exists(bob.resolve("outside.txt")));
    }

    private static @NonNull Entry<@NonNull Short, @NonNull List<@NonNull ByteBuffer>>
            nativeAclSnapshot(@NonNull Path path) {
        var api = Native.load("advapi32", WindowsWorkspaceSecurity.WindowsAclApi.class);
        var acl = new PointerByReference();
        var descriptor = new PointerByReference();
        try {
            assertEquals(
                    0,
                    api.GetNamedSecurityInfoW(
                            new WString(path.toString()), 1, 4, null, null, acl, null, descriptor));
            var pointer = Objects.requireNonNull(acl.getValue());
            byte[] bytes = pointer.getByteArray(0, Short.toUnsignedInt(pointer.getShort(2)));
            var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            List<@NonNull ByteBuffer> entries = new ArrayList<>();
            int offset = 8;
            for (int index = 0; index < Short.toUnsignedInt(data.getShort(4)); index++) {
                int size = Short.toUnsignedInt(data.getShort(offset + 2));
                entries.add(
                        ByteBuffer.wrap(Arrays.copyOfRange(bytes, offset, offset + size))
                                .asReadOnlyBuffer());
                offset += size;
            }
            return Map.entry(Objects.requireNonNull(descriptor.getValue()).getShort(2), entries);
        } finally {
            if (descriptor.getValue() != null) Kernel32.INSTANCE.LocalFree(descriptor.getValue());
        }
    }

    private static void allowApplicationPackages(@NonNull Path directory) {
        List<@NonNull String> sids = new ArrayList<>(List.of("S-1-15-2-1", "S-1-15-2-2"));
        sids.addAll(WindowsAppContainerLauncher.NETWORK_CAPABILITY_SIDS);
        for (String sid : sids) {
            var api = Native.load("advapi32", WindowsWorkspaceSecurity.WindowsAclApi.class);
            var nativeSid = new PointerByReference();
            try {
                assertTrue(api.ConvertStringSidToSidW(new WString(sid), nativeSid));
                allowSid(directory, Objects.requireNonNull(nativeSid.getValue()));
            } finally {
                if (nativeSid.getValue() != null) Kernel32.INSTANCE.LocalFree(nativeSid.getValue());
            }
        }
    }

    private static void allowSandboxIdentity(@NonNull Path directory, @NonNull Path workspace) {
        var containers =
                Native.load("userenv", WindowsWorkspaceSecurity.WindowsAppContainerApi.class);
        var api = Native.load("advapi32", WindowsWorkspaceSecurity.WindowsAclApi.class);
        var sid = new PointerByReference();
        try {
            assertEquals(
                    0,
                    containers.DeriveAppContainerSidFromAppContainerName(
                            new WString(WindowsWorkspaceSecurity.appContainerName(workspace)),
                            sid));
            allowSid(directory, Objects.requireNonNull(sid.getValue()));
        } finally {
            if (sid.getValue() != null) api.FreeSid(sid.getValue());
        }
    }

    private static void allowSid(@NonNull Path directory, @NonNull Pointer sid) {
        var api = Native.load("advapi32", WindowsWorkspaceSecurity.WindowsAclApi.class);
        var acl = new PointerByReference();
        var descriptor = new PointerByReference();
        var updated = new PointerByReference();
        try {
            var name = new WString(directory.toString());
            assertEquals(
                    0, api.GetNamedSecurityInfoW(name, 1, 4, null, null, acl, null, descriptor));
            var allow = WindowsWorkspaceSecurity.explicitSidAccess(sid, 0x001F01FF, 1, 3);
            assertEquals(0, api.SetEntriesInAclW(1, allow, acl.getValue(), updated));
            assertEquals(
                    0, api.SetNamedSecurityInfoW(name, 1, 4, null, null, updated.getValue(), null));
        } finally {
            if (updated.getValue() != null) Kernel32.INSTANCE.LocalFree(updated.getValue());
            if (descriptor.getValue() != null) Kernel32.INSTANCE.LocalFree(descriptor.getValue());
        }
    }

    private static void assertWorkspaceProbe(
            @NonNull ConstrainedSubprocessSubstrate substrate,
            @NonNull SandboxHandle handle,
            @NonNull Path probeRoot,
            @NonNull Path inside,
            @NonNull Path outside,
            @NonNull Path secret,
            @NonNull String expectedRead) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        var result =
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
                                                inside.toString(),
                                                outside.toString(),
                                                secret.toString()))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(20));
        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("inside-write=allowed"), result.stdout());
        assertTrue(result.stdout().contains("outside-write=denied"), result.stdout());
        assertTrue(result.stdout().contains("outside-read=" + expectedRead), result.stdout());
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
                        workspace, 512, 100, 8, Duration.ofSeconds(30), Set.of(protectedFile));
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
                        workspace, 512, 100, 8, Duration.ofSeconds(30), Set.of(protectedFile));
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
                        workspace, 512, 100, 8, Duration.ofSeconds(30), Set.of(protectedFile));
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
                    byte[] body = "network-escape".getBytes(StandardCharsets.UTF_8);
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
                        Set.of(),
                        Set.of(),
                        Set.of());
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
                        List.of(
                                new Command("cmd", List.of("/c", "echo", "sandbox-pipeline")),
                                new Command("findstr", List.of("sandbox-pipeline"))),
                        Path.of("."),
                        ChainMode.PIPE,
                        Duration.ofSeconds(20));

        assertEquals(
                0, result.exitCode(), "stdout=" + result.stdout() + "; stderr=" + result.stderr());
        assertTrue(result.stdout().contains("sandbox-pipeline"), result.stdout());
        assertEquals(List.of(0, 0), result.perCommand());
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
                        new Command("cmd", List.of("/c", "echo", "background-ok")),
                        Path.of("."));

        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
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
