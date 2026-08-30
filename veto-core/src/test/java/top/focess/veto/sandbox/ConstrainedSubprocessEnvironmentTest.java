package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConstrainedSubprocessEnvironmentTest {

    @Test
    void keepsArbitraryHostVariablesAndOverridesOnlySandboxOwnedValues(
            @TempDir @NonNull Path workspace) {
        Map<String, String> environment = new HashMap<>();
        environment.put("VETO_ARBITRARY_HOST_VALUE", "preserved");
        environment.put("TEMP", "host-temp");
        SandboxProfile profile = new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30));

        ConstrainedSubprocessSubstrate.configureEnvironment(environment, profile);

        assertEquals("preserved", environment.get("VETO_ARBITRARY_HOST_VALUE"));
        String sandboxTemp =
                workspace.resolve(".veto/sandbox-tmp").toAbsolutePath().normalize().toString();
        assertEquals(sandboxTemp, environment.get("TEMP"));
        assertEquals(sandboxTemp, environment.get("TMP"));
        assertEquals(sandboxTemp, environment.get("TMPDIR"));
    }

    @Test
    void resolvesDirectExecutableFromTheHostTerminalPath(@TempDir @NonNull Path workspace) {
        String resolved = ConstrainedSubprocessSubstrate.resolveExecutable("java", workspace);

        Path executable = Path.of(resolved);
        assertTrue(executable.isAbsolute(), resolved);
        assertTrue(Files.isRegularFile(executable), resolved);
    }

    @Test
    void resolvesExplicitRelativeExecutableAgainstTheAuthorizedWorkingDirectory(
            @TempDir @NonNull Path workspace) {
        String name =
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? ".\\tool.exe"
                        : "./tool";

        String resolved = ConstrainedSubprocessSubstrate.resolveExecutable(name, workspace);

        assertEquals(workspace.resolve(name).toAbsolutePath().normalize().toString(), resolved);
    }

    @Test
    void discoversPathValuedEnvironmentWithoutRuntimeNameSpecialCases(
            @TempDir @NonNull Path workspace) throws Exception {
        Path toolRoot = Files.createDirectory(workspace.resolve("tool-root"));
        Path cacheRoot = Files.createDirectory(workspace.resolve("cache-root"));
        String pathValue = toolRoot + java.io.File.pathSeparator + "not-a-path";

        SandboxProfile.EnvironmentRoots roots =
                SandboxProfile.EnvironmentRoots.discover(
                        Map.of(
                                "ANY_TOOL_PATH",
                                pathValue,
                                "ANY_CACHE_LOCATION",
                                cacheRoot.toString(),
                                "ORDINARY_VALUE",
                                "not-a-path"));

        assertTrue(roots.readExecute().contains(toolRoot.toAbsolutePath().normalize()));
        assertTrue(roots.readExecute().contains(cacheRoot.toAbsolutePath().normalize()));
        assertTrue(roots.readWriteExecute().contains(cacheRoot.toAbsolutePath().normalize()));
    }

    @Test
    void rejectsWindowsCommandShimMetacharactersBeforeComSpec() {
        assertThrows(
                SecurityException.class,
                () ->
                        ConstrainedSubprocessSubstrate.validateWindowsCommandShimToken(
                                "safe&whoami"));
        ConstrainedSubprocessSubstrate.validateWindowsCommandShimToken("--project-dir");
    }
}
