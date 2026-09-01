package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.TrustMarker;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.agent.workspace.WorkspaceRoot;
import top.focess.veto.llm.core.ToolCall;

/** Tests for the Part 3.6 group/multi-user deployer policies (SANDBOXED, TENANT). */
class DeployerPolicyTest {

    @Test
    void sandboxedOutOfRootIsCritical(@TempDir @NonNull Path tmp) throws Exception {
        Path canonical = tmp.toRealPath();
        Path root = canonical.resolve("deploy-zone");
        Files.createDirectories(root);
        Workspace ws = Workspace.single(root, PathMode.REAL);
        NativeToolDefinition readDef = readDef();
        ToolCall call =
                new ToolCall(
                        "view_file",
                        Map.of("path", canonical.resolve("outside/secret.txt").toString()));
        Danger danger =
                new DangerComputation()
                        .compute(readDef, call, ws, DeployerPolicy.SANDBOXED, ProtectedSet.empty());
        assertEquals(Danger.CRITICAL, danger, "SANDBOXED must refuse out-of-root paths");
    }

    @Test
    void sandboxedInRootIsNotCritical(@TempDir @NonNull Path tmp) throws Exception {
        Path canonical = tmp.toRealPath();
        Path root = canonical.resolve("deploy-zone");
        Files.createDirectories(root);
        Files.writeString(root.resolve("ok.txt"), "hello");
        Workspace ws = Workspace.single(root, PathMode.REAL);
        ToolCall call =
                new ToolCall("view_file", Map.of("path", root.resolve("ok.txt").toString()));
        Danger danger =
                new DangerComputation()
                        .compute(
                                readDef(),
                                call,
                                ws,
                                DeployerPolicy.SANDBOXED,
                                ProtectedSet.empty());
        assertNotEquals(Danger.CRITICAL, danger, "SANDBOXED in-root reads must not be CRITICAL");
    }

    @Test
    void tenantNonSharedCrossUserIsCritical(@TempDir @NonNull Path tmp) throws Exception {
        // Two roots: alice's own + bob's (not shared)
        Path canonical = tmp.toRealPath();
        Path aliceRoot = canonical.resolve("alice");
        Path bobRoot = canonical.resolve("bob");
        Files.createDirectories(aliceRoot);
        Files.createDirectories(bobRoot);
        Files.writeString(bobRoot.resolve("private.txt"), "bob's data");
        Workspace ws =
                new Workspace(
                        List.of(
                                WorkspaceRoot.of(aliceRoot, TrustMarker.OWNED),
                                WorkspaceRoot.of(bobRoot, TrustMarker.OWNED)),
                        PathMode.REAL,
                        0);
        // Alice (currentRootIndex=0) tries to read Bob's file.
        ToolCall call =
                new ToolCall(
                        "view_file", Map.of("path", bobRoot.resolve("private.txt").toString()));
        Danger danger =
                new DangerComputation()
                        .compute(readDef(), call, ws, DeployerPolicy.TENANT, ProtectedSet.empty());
        assertEquals(Danger.CRITICAL, danger, "TENANT must refuse non-shared cross-user paths");
    }

    @Test
    void tenantSharedGrantAllowsRead(@TempDir @NonNull Path tmp) throws Exception {
        // Alice's own + Bob's (SHARED_GRANT to alice)
        Path canonical = tmp.toRealPath();
        Path aliceRoot = canonical.resolve("alice");
        Path bobRoot = canonical.resolve("bob");
        Files.createDirectories(aliceRoot);
        Files.createDirectories(bobRoot);
        Files.writeString(bobRoot.resolve("shared.txt"), "shared content");
        Workspace ws =
                new Workspace(
                        List.of(
                                WorkspaceRoot.of(aliceRoot, TrustMarker.OWNED),
                                WorkspaceRoot.of(bobRoot, TrustMarker.SHARED_GRANT)),
                        PathMode.REAL,
                        0);
        ToolCall call =
                new ToolCall("view_file", Map.of("path", bobRoot.resolve("shared.txt").toString()));
        Danger danger =
                new DangerComputation()
                        .compute(readDef(), call, ws, DeployerPolicy.TENANT, ProtectedSet.empty());
        assertNotEquals(
                Danger.CRITICAL,
                danger,
                "TENANT must allow reads on a SHARED_GRANT root (the user can read what was shared)");
    }

    @Test
    void protectedSetStillAppliesUnderSandboxed(@TempDir @NonNull Path tmp) throws Exception {
        Path canonical = tmp.toRealPath();
        Path root = canonical.resolve("deploy-zone");
        Files.createDirectories(root.resolve(".ssh"));
        Files.writeString(root.resolve(".ssh/id_rsa"), "secret");
        Workspace ws = Workspace.single(root, PathMode.REAL);
        // .ssh is in the protected set; even within the deploy zone, the read is CRITICAL.
        ProtectedSet ps = new ProtectedSet(java.util.Set.of(root.resolve(".ssh")));
        ToolCall call =
                new ToolCall("view_file", Map.of("path", root.resolve(".ssh/id_rsa").toString()));
        Danger danger =
                new DangerComputation().compute(readDef(), call, ws, DeployerPolicy.SANDBOXED, ps);
        assertEquals(Danger.CRITICAL, danger, "SANDBOXED + protected-set path → CRITICAL");
    }

    @Test
    void sharedGrantsWithDeployerDefaults(@TempDir @NonNull Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        ProtectedSet ps =
                ProtectedSet.withSharedGrants(
                        "alice",
                        List.of(root),
                        List.of(
                                new ProtectedSet.SharedGrant(
                                        root, ProtectedSet.GrantMode.READ_ONLY)));
        assertTrue(ps.paths().stream().anyMatch(p -> p.toString().contains(".veto")));
        assertTrue(ps.paths().contains(root));
    }

    private @NonNull NativeToolDefinition readDef() {
        return new NativeToolDefinition(
                "view_file",
                "read",
                RiskCategory.READ_ONLY,
                false,
                ToolDocs.nonNullClass(Object.class),
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }
}
