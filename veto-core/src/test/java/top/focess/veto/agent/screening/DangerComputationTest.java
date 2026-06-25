package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

class DangerComputationTest {

    // JUnit's @TempDir may sit under a junction / 8.3 short-name (Windows: ADMINI~1) so its lexical
    // path differs from toRealPath(); the REAL-mode PathResolver canonicalizes candidates via
    // toRealPath(), so the root must be canonicalized to match (same accommodation as
    // PathResolverTest#canonicalizeTempDir). Without this, in-scope temp paths are misclassified
    // out-of-scope (CRITICAL) on Windows.
    @TempDir Path root;

    @BeforeEach
    void canonicalizeRoot() throws Exception {
        root = root.toRealPath();
    }

    private DangerComputation dc = new DangerComputation();

    private Workspace ws(Path root) {
        return Workspace.single(root, PathMode.REAL);
    }

    private NativeToolDefinition readDef() {
        return new NativeToolDefinition(
                "view_file",
                "read",
                RiskCategory.READ_ONLY,
                false,
                ReadArgs.class,
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    private NativeToolDefinition writeDef() {
        return new NativeToolDefinition(
                "write_to_file",
                "write",
                RiskCategory.FILE_WRITE,
                false,
                WriteArgs.class,
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    // minimal arg record classes for the defs
    public record ReadArgs(String path) {}

    public record WriteArgs(String path, String content) {}

    @Test
    void inProjectReadIsSafe() throws Exception {
        Files.createDirectories(root.resolve("src"));
        ToolCall call =
                new ToolCall("view_file", Map.of("path", root.resolve("src/Main.java").toString()));
        assertEquals(
                Danger.SAFE,
                dc.compute(readDef(), call, ws(root), DeployerPolicy.FULL, ProtectedSet.empty()));
    }

    @Test
    void outOfScopePathIsCritical() throws Exception {
        ToolCall call =
                new ToolCall(
                        "view_file", Map.of("path", root.resolve("../../etc/passwd").toString()));
        assertEquals(
                Danger.CRITICAL,
                dc.compute(readDef(), call, ws(root), DeployerPolicy.FULL, ProtectedSet.empty()));
    }

    @Test
    void secretLocationReadIsDangerous() throws Exception {
        Path secret = root.resolve(".env");
        Files.writeString(secret, "KEY=v");
        ToolCall call = new ToolCall("view_file", Map.of("path", secret.toString()));
        assertEquals(
                Danger.DANGEROUS,
                dc.compute(readDef(), call, ws(root), DeployerPolicy.FULL, ProtectedSet.empty()));
    }

    @Test
    void protectedPathUnderProtectSensitiveIsCritical() throws Exception {
        Path protectedFile = root.resolve(".ssh/id_rsa");
        Files.createDirectories(protectedFile.getParent());
        Files.writeString(protectedFile, "k");
        ProtectedSet ps = new ProtectedSet(java.util.Set.of(root.resolve(".ssh")));
        ToolCall call = new ToolCall("view_file", Map.of("path", protectedFile.toString()));
        assertEquals(
                Danger.CRITICAL,
                dc.compute(readDef(), call, ws(root), DeployerPolicy.PROTECT_SENSITIVE, ps));
    }

    @Test
    void protectedPathHasNoEffectUnderFull() throws Exception {
        Path protectedFile = root.resolve(".ssh/id_rsa");
        Files.createDirectories(protectedFile.getParent());
        Files.writeString(protectedFile, "k");
        ProtectedSet ps = new ProtectedSet(java.util.Set.of(root.resolve(".ssh")));
        ToolCall call = new ToolCall("view_file", Map.of("path", protectedFile.toString()));
        // under FULL the protected set is not consulted — but .ssh is also a secret-location →
        // DANGEROUS
        assertEquals(
                Danger.DANGEROUS, dc.compute(readDef(), call, ws(root), DeployerPolicy.FULL, ps));
    }

    @Test
    void writeIsElevatedBaseline() throws Exception {
        Files.createDirectories(root.resolve("src"));
        ToolCall call =
                new ToolCall(
                        "write_to_file",
                        Map.of("path", root.resolve("src/Main.java").toString(), "content", "x"));
        assertEquals(
                Danger.ELEVATED,
                dc.compute(writeDef(), call, ws(root), DeployerPolicy.FULL, ProtectedSet.empty()));
    }

    @Test
    void blacklistedExecutableIsCritical() throws Exception {
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        ExecArgs.class,
                        Map.of());
        ToolCall call =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                List.of(Map.of("executable", "nc", "args", List.of("-l"))),
                                "cwd",
                                root.toString()));
        assertEquals(
                Danger.CRITICAL,
                dc.compute(execDef, call, ws(root), DeployerPolicy.FULL, ProtectedSet.empty()));
    }

    public record ExecArgs(Map<String, Object> commands, String cwd) {}
}
