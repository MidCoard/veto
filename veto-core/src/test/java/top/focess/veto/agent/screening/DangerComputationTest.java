package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

@SuppressWarnings("initialization.field.uninitialized")
class DangerComputationTest {

    // JUnit's @TempDir may sit under a junction / 8.3 short-name (Windows: ADMINI~1) so its lexical
    // path differs from toRealPath(); the REAL-mode PathResolver canonicalizes candidates via
    // toRealPath(), so the root must be canonicalized to match (same accommodation as
    // PathResolverTest#canonicalizeTempDir). Without this, in-scope temp paths are misclassified
    // out-of-scope (CRITICAL) on Windows.
    @TempDir @NonNull Path root;

    @BeforeEach
    void canonicalizeRoot() throws Exception {
        root = root.toRealPath();
    }

    private final @NonNull DangerComputation dc = new DangerComputation();

    private @NonNull Workspace ws(@NonNull Path root) {
        return Workspace.single(root, PathMode.REAL);
    }

    private @NonNull NativeToolDefinition readDef() {
        return new NativeToolDefinition(
                "view_file",
                "read",
                RiskCategory.READ_ONLY,
                false,
                ToolDocs.nonNullClass(ReadArgs.class),
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    private @NonNull NativeToolDefinition writeDef() {
        return new NativeToolDefinition(
                "write_to_file",
                "write",
                RiskCategory.FILE_WRITE,
                false,
                ToolDocs.nonNullClass(WriteArgs.class),
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
                dc.compute(
                        readDef(),
                        call,
                        ws(root),
                        DeployerPolicy.FULL_ACCESS,
                        ProtectedSet.empty()));
    }

    @Test
    void outOfScopePathIsCritical() throws Exception {
        ToolCall call =
                new ToolCall(
                        "view_file", Map.of("path", root.resolve("../../etc/passwd").toString()));
        assertEquals(
                Danger.CRITICAL,
                dc.compute(
                        readDef(), call, ws(root), DeployerPolicy.SANDBOXED, ProtectedSet.empty()));
    }

    @Test
    void secretLocationReadIsDangerous() throws Exception {
        Path secret = root.resolve(".env");
        Files.writeString(secret, "KEY=v");
        ToolCall call = new ToolCall("view_file", Map.of("path", secret.toString()));
        assertEquals(
                Danger.DANGEROUS,
                dc.compute(
                        readDef(),
                        call,
                        ws(root),
                        DeployerPolicy.FULL_ACCESS,
                        ProtectedSet.empty()));
    }

    @Test
    void protectedPathUnderProtectSensitiveIsCritical() throws Exception {
        Path protectedFile = root.resolve(".ssh/id_rsa");
        Files.createDirectories(parentOf(protectedFile));
        Files.writeString(protectedFile, "k");
        ProtectedSet ps = new ProtectedSet(java.util.Set.of(root.resolve(".ssh")));
        ToolCall call = new ToolCall("view_file", Map.of("path", protectedFile.toString()));
        assertEquals(
                Danger.CRITICAL,
                dc.compute(readDef(), call, ws(root), DeployerPolicy.PROTECTED, ps));
    }

    @Test
    void protectedPathHasNoEffectUnderFull() throws Exception {
        Path protectedFile = root.resolve(".ssh/id_rsa");
        Files.createDirectories(parentOf(protectedFile));
        Files.writeString(protectedFile, "k");
        ProtectedSet ps = new ProtectedSet(java.util.Set.of(root.resolve(".ssh")));
        ToolCall call = new ToolCall("view_file", Map.of("path", protectedFile.toString()));
        // under FULL the protected set is not consulted — but .ssh is also a secret-location →
        // DANGEROUS
        assertEquals(
                Danger.DANGEROUS,
                dc.compute(readDef(), call, ws(root), DeployerPolicy.FULL_ACCESS, ps));
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
                dc.compute(
                        writeDef(),
                        call,
                        ws(root),
                        DeployerPolicy.FULL_ACCESS,
                        ProtectedSet.empty()));
    }

    @Test
    void blacklistedExecutableIsCritical() throws Exception {
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        ToolDocs.nonNullClass(ExecArgs.class),
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
                dc.compute(
                        execDef, call, ws(root), DeployerPolicy.FULL_ACCESS, ProtectedSet.empty()));
    }

    @Test
    void pathQualifiedBlacklistedExecutableIsCritical() throws Exception {
        // A path-qualified blacklisted executable (/usr/bin/nc) must still classify CRITICAL
        // (grant-immune) after baseName normalization — not fall through to the grantable
        // DANGEROUS bucket. Regression guard for the deleted Gateway.baseName().
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        ToolDocs.nonNullClass(ExecArgs.class),
                        Map.of());
        ToolCall call =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                List.of(Map.of("executable", "/usr/bin/nc", "args", List.of("-l"))),
                                "cwd",
                                root.toString()));
        assertEquals(
                Danger.CRITICAL,
                dc.compute(
                        execDef, call, ws(root), DeployerPolicy.FULL_ACCESS, ProtectedSet.empty()));
    }

    @Test
    void tenantPolicySharedGrantWriteIsCritical() throws Exception {
        Path ownedRoot = root.resolve("owned");
        Path sharedRoot = root.resolve("shared");
        Files.createDirectories(ownedRoot);
        Files.createDirectories(sharedRoot);

        top.focess.veto.agent.workspace.WorkspaceRoot owned =
                top.focess.veto.agent.workspace.WorkspaceRoot.probe(
                        ownedRoot, top.focess.veto.agent.workspace.TrustMarker.OWNED);
        top.focess.veto.agent.workspace.WorkspaceRoot shared =
                top.focess.veto.agent.workspace.WorkspaceRoot.probe(
                        sharedRoot, top.focess.veto.agent.workspace.TrustMarker.SHARED_GRANT);

        Workspace ws = new Workspace(List.of(owned, shared), PathMode.REAL, 0);

        // A write to owned is ELEVATED
        ToolCall writeOwnedCall =
                new ToolCall(
                        "write_to_file",
                        Map.of("path", ownedRoot.resolve("file.txt").toString(), "content", "x"));
        assertEquals(
                Danger.ELEVATED,
                dc.compute(
                        writeDef(),
                        writeOwnedCall,
                        ws,
                        DeployerPolicy.TENANT,
                        ProtectedSet.empty()));

        // A write to shared (SHARED_GRANT) under TENANT is CRITICAL
        ToolCall writeSharedCall =
                new ToolCall(
                        "write_to_file",
                        Map.of("path", sharedRoot.resolve("file.txt").toString(), "content", "x"));
        assertEquals(
                Danger.CRITICAL,
                dc.compute(
                        writeDef(),
                        writeSharedCall,
                        ws,
                        DeployerPolicy.TENANT,
                        ProtectedSet.empty()));

        // A read from shared (SHARED_GRANT) under TENANT is SAFE
        ToolCall readSharedCall =
                new ToolCall(
                        "view_file", Map.of("path", sharedRoot.resolve("file.txt").toString()));
        assertEquals(
                Danger.SAFE,
                dc.compute(
                        readDef(),
                        readSharedCall,
                        ws,
                        DeployerPolicy.TENANT,
                        ProtectedSet.empty()));
    }

    @Test
    void curlIsDangerousNotCritical() throws Exception {
        // curl is off the blacklist: it stays DANGEROUS via the network-scan rule (HITL-gated,
        // grantable — e.g. a localhost API test) instead of auto-blocked CRITICAL.
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        ToolDocs.nonNullClass(ExecArgs.class),
                        Map.of());
        ToolCall call =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                List.of(
                                        Map.of(
                                                "executable",
                                                "curl",
                                                "args",
                                                List.of("http://localhost:5188/"))),
                                "cwd",
                                root.toString()));
        assertEquals(
                Danger.DANGEROUS,
                dc.compute(
                        execDef, call, ws(root), DeployerPolicy.FULL_ACCESS, ProtectedSet.empty()));
    }

    @Test
    void processKillerIsDangerous() throws Exception {
        // Process termination (taskkill) is always a user decision: DANGEROUS (grantable), never
        // auto-approved and never CRITICAL.
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        ToolDocs.nonNullClass(ExecArgs.class),
                        Map.of());
        ToolCall call =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                List.of(
                                        Map.of(
                                                "executable",
                                                "taskkill.exe",
                                                "args",
                                                List.of("/F", "/PID", "1234"))),
                                "cwd",
                                root.toString()));
        assertEquals(
                Danger.DANGEROUS,
                dc.compute(
                        execDef, call, ws(root), DeployerPolicy.FULL_ACCESS, ProtectedSet.empty()));
    }

    public record ExecArgs(Map<String, Object> commands, String cwd) {}

    private static @NonNull Path parentOf(@NonNull Path path) {
        Path parent = path.getParent();
        if (parent == null) throw new AssertionError("expected parent for " + path);
        return parent;
    }
}
