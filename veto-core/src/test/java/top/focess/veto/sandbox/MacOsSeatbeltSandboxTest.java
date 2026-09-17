package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class MacOsSeatbeltSandboxTest {

    @Test
    void profileIsDefaultDenyAndOnlyMakesWorkspaceWritable() {
        Path workspace = Path.of("workspace with space");

        String profile = MacOsSeatbeltSandbox.profile(workspace);

        assertTrue(profile.startsWith("(version 1)\n(deny default)"));
        assertTrue(profile.contains("workspace with space"));
        assertTrue(profile.contains("(allow file-write* (subpath"));
        assertTrue(profile.contains("(deny file-write* (subpath"));
        assertTrue(profile.contains(".agents"));
        assertFalse(profile.contains(".git"));
        assertFalse(profile.contains("(allow network"));
        assertFalse(profile.contains("(allow file-read*)"));
        assertFalse(profile.contains("(allow sysctl-read)"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macOsBackendRunsInsideWorkspace(@TempDir @NonNull Path workspace) {
        KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
        SandboxHandle handle = substrate.provision(profile(workspace));

        CommandResult result =
                substrate.runCommands(
                        handle,
                        List.of(new Command("/usr/bin/printf", List.of("seatbelt-ok"))),
                        Path.of("."),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(10));

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("seatbelt-ok", result.stdout());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macOsBackendDeniesReadOutsideWorkspace(@TempDir @NonNull Path workspace) throws Exception {
        Path outside = Files.createTempFile("veto-seatbelt-secret", ".txt");
        Files.writeString(outside, "must-not-be-readable");
        try {
            KernelSandboxSubstrate kernel = new KernelSandboxSubstrate();
            ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate(kernel);
            SandboxHandle handle = substrate.provision(profile(workspace));

            CommandResult result =
                    substrate.runCommands(
                            handle,
                            List.of(new Command("/bin/cat", List.of(outside.toString()))),
                            Path.of("."),
                            ChainMode.STOP_ON_FAILURE,
                            Duration.ofSeconds(10));

            assertNotEquals(0, result.exitCode());
            assertFalse(result.stdout().contains("must-not-be-readable"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void mkdirTraversesWorkspaceAncestorsButCannotWriteOutside(@TempDir @NonNull Path temp)
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        var substrate = new ConstrainedSubprocessSubstrate(new KernelSandboxSubstrate());
        var handle = substrate.provision(profile(workspace));
        try {
            Path child = workspace.resolve("nested/child");
            var created =
                    substrate.runCommands(
                            handle,
                            List.of(new Command("/bin/mkdir", List.of("-p", child.toString()))),
                            Path.of("."),
                            ChainMode.STOP_ON_FAILURE,
                            Duration.ofSeconds(10));
            assertEquals(0, created.exitCode(), created.stderr());
            assertTrue(Files.isDirectory(child));
            Path outside = temp.resolve("outside");
            var denied =
                    substrate.runCommands(
                            handle,
                            List.of(new Command("/usr/bin/touch", List.of(outside.toString()))),
                            Path.of("."),
                            ChainMode.STOP_ON_FAILURE,
                            Duration.ofSeconds(10));
            assertNotEquals(0, denied.exitCode());
            assertFalse(Files.exists(outside));
        } finally {
            substrate.deprovision(handle);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void executableSymlinkParentsPermitMetadataButNotSiblingReadsOrWrites(
            @TempDir @NonNull Path temp) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path links = Files.createDirectories(temp.resolve("discovery/bin"));
        Path runtime = Files.createDirectories(temp.resolve("installation/version/bin"));
        Path executable = runtime.resolve("probe");
        Files.writeString(
                executable,
                """
                #!/bin/sh
                /usr/bin/stat -f '%N' "$1" "$2" || exit 10
                if /bin/cat "$3"; then exit 11; fi
                if /usr/bin/touch "$4"; then exit 12; fi
                if /bin/ls "$2"; then exit 13; fi
                /usr/bin/printf 'metadata-only-ok'
                """);
        assertTrue(executable.toFile().setExecutable(true, true));
        Path discovered = Files.createSymbolicLink(links.resolve("probe"), executable);
        Path secret = Files.writeString(runtime.resolve("private.txt"), "unrelated-private-data");
        Path forbiddenWrite = runtime.resolve("created.txt");
        var substrate = new ConstrainedSubprocessSubstrate(new KernelSandboxSubstrate());
        var handle = substrate.provision(profile(workspace));
        try {
            var result =
                    substrate.runCommands(
                            handle,
                            List.of(
                                    new Command(
                                            discovered.toString(),
                                            List.of(
                                                    links.toString(),
                                                    runtime.toString(),
                                                    secret.toString(),
                                                    forbiddenWrite.toString()))),
                            Path.of("."),
                            ChainMode.STOP_ON_FAILURE,
                            Duration.ofSeconds(10));
            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(result.stdout().contains("metadata-only-ok"));
            assertFalse(result.stdout().contains("unrelated-private-data"));
            assertFalse(result.stdout().contains("private.txt"));
            assertFalse(Files.exists(forbiddenWrite));
        } finally {
            substrate.deprovision(handle);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void childRuntimeCanInspectReadOnlyRootAncestorsWithoutReadingOrWritingSiblings(
            @TempDir @NonNull Path temp) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path distribution = Files.createDirectories(temp.resolve("distribution"));
        Path parent = Files.createDirectories(distribution.resolve("versions"));
        Path runtime = Files.createDirectories(parent.resolve("current"));
        Path executable = runtime.resolve("probe");
        Files.writeString(
                executable,
                """
                #!/bin/sh
                /usr/bin/stat -f '%N' "$1" "$2" || exit 10
                if /bin/cat "$3"; then exit 11; fi
                if /usr/bin/touch "$4"; then exit 12; fi
                if /bin/ls "$2"; then exit 13; fi
                /usr/bin/printf 'child-runtime-ok'
                """);
        assertTrue(executable.toFile().setExecutable(true, true));
        Path secret = Files.writeString(parent.resolve("private.txt"), "unrelated-private-data");
        Path forbiddenWrite = parent.resolve("created.txt");
        String profile =
                MacOsSeatbeltSandbox.profile(workspace)
                        + "\n"
                        + MacOsSeatbeltSandbox.readOnlySubtreeRules(runtime);
        var child =
                new ProcessBuilder(
                                "/usr/bin/sandbox-exec",
                                "-p",
                                profile,
                                "/usr/bin/env",
                                executable.toString(),
                                distribution.toString(),
                                parent.toString(),
                                secret.toString(),
                                forbiddenWrite.toString())
                        .directory(workspace.toFile())
                        .redirectErrorStream(true)
                        .start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            String output = new String(child.getInputStream().readAllBytes());
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("child-runtime-ok"));
            assertFalse(output.contains("unrelated-private-data"));
            assertFalse(Files.exists(forbiddenWrite));
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void missingReadOnlyPathRetainsItsCanonicalAliasWithoutGrantingTheParentSubtree(
            @TempDir @NonNull Path temp) throws Exception {
        Path canonicalParent = Files.createDirectories(temp.resolve("settings"));
        Path alias = Files.createSymbolicLink(temp.resolve("settings-alias"), canonicalParent);
        Path missing = alias.resolve("not-selected");

        String rules = MacOsSeatbeltSandbox.readOnlyPathRules(missing);

        assertTrue(rules.contains("(literal \"" + missing + "\")"));
        assertTrue(
                rules.contains(
                        "(literal \""
                                + canonicalParent.toRealPath().resolve("not-selected")
                                + "\")"));
        assertTrue(rules.contains("(allow file-read-metadata (literal \"" + alias + "\"))"));
        assertFalse(rules.contains("subpath"));
        assertFalse(rules.contains("file-write"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void readOnlySymlinkIncludesItsCanonicalEntryAndTarget(@TempDir @NonNull Path temp)
            throws Exception {
        Path settings = Files.createDirectories(temp.resolve("settings"));
        Path alias = Files.createSymbolicLink(temp.resolve("settings-alias"), settings);
        Path target = Files.writeString(temp.resolve("selected-toolchain"), "selected");
        Path selector = Files.createSymbolicLink(settings.resolve("selector"), target);

        String rules = MacOsSeatbeltSandbox.readOnlyPathRules(alias.resolve("selector"));

        assertTrue(rules.contains("(literal \"" + alias.resolve("selector") + "\")"));
        assertTrue(
                rules.contains("(literal \"" + settings.toRealPath().resolve("selector") + "\")"));
        assertTrue(rules.contains("(literal \"" + selector.toRealPath() + "\")"));
        assertFalse(rules.contains("subpath"));
        assertFalse(rules.contains("file-write"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void developerToolSelectionMatchesTheHostWithoutBroadVarAccess(@TempDir @NonNull Path workspace)
            throws Exception {
        var outside = new ProcessBuilder("/usr/bin/xcode-select", "-p").start();
        String expectedOutput = new String(outside.getInputStream().readAllBytes());
        String expectedError = new String(outside.getErrorStream().readAllBytes());
        int expectedExit = outside.waitFor();
        var substrate = new ConstrainedSubprocessSubstrate(new KernelSandboxSubstrate());
        var handle = substrate.provision(profile(workspace));
        try {
            var result =
                    substrate.runCommands(
                            handle,
                            List.of(new Command("/usr/bin/xcode-select", List.of("-p"))),
                            Path.of("."),
                            ChainMode.STOP_ON_FAILURE,
                            Duration.ofSeconds(10));
            assertEquals(expectedExit, result.exitCode(), result.stderr());
            assertEquals(expectedOutput, result.stdout());
            assertEquals(expectedError, result.stderr());
            assertFalse(MacOsSeatbeltSandbox.profile(workspace).contains("(subpath \"/var\")"));
            assertFalse(
                    MacOsSeatbeltSandbox.profile(workspace).contains("(subpath \"/private/var\")"));
        } finally {
            substrate.deprovision(handle);
        }
    }

    private static int occurrences(@NonNull String value, @NonNull String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static @NonNull SandboxProfile profile(@NonNull Path workspace) {
        return new SandboxProfile(workspace, 512, 100, 16, Duration.ofSeconds(30));
    }
}
