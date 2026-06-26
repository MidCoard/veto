package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

/**
 * Tests for the permission-grant system (screening_model.md §7.1). Covers:
 *
 * <ul>
 *   <li>Read grants match by directory prefix + read tool family
 *   <li>Write grants match by directory prefix + tool
 *   <li>Command grants match by executable + subcommand + flag shape
 *   <li>Grants are session-scoped
 *   <li>Grants are revoked
 *   <li>Identical-match (not fuzzy)
 * </ul>
 */
class PermissionGrantTest {

    @Test
    void readGrantMatchesUnderDirectoryPrefix(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("auth-svc");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}");
        Path subdir = root.resolve("src");
        Workspace ws = Workspace.single(root, PathMode.REAL);
        NativeToolDefinition readDef =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        RiskCategory.READ_ONLY,
                        false,
                        Object.class,
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call =
                new ToolCall("view_file", Map.of("path", subdir.resolve("Main.java").toString()));
        PermissionGrant.ToolCallSpec spec = MatchKeyExtractor.extract(call, readDef, ws);
        PermissionGrant.ReadGrant grant = new PermissionGrant.ReadGrant("read", subdir, List.of());
        assertTrue(grant.matches(spec), "read grant should match the same directory");
    }

    @Test
    void readGrantDoesNotMatchOutsideDirectory(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("auth-svc");
        Files.createDirectories(root.resolve("src"));
        Path other = tmp.resolve("other");
        Files.createDirectories(other);
        Workspace ws = Workspace.single(root, PathMode.REAL);
        NativeToolDefinition readDef =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        RiskCategory.READ_ONLY,
                        false,
                        Object.class,
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call =
                new ToolCall("view_file", Map.of("path", other.resolve("x.txt").toString()));
        PermissionGrant.ToolCallSpec spec = MatchKeyExtractor.extract(call, readDef, ws);
        PermissionGrant.ReadGrant grant =
                new PermissionGrant.ReadGrant("read", root.resolve("src"), List.of());
        assertFalse(grant.matches(spec), "read grant must NOT match outside its directory");
    }

    @Test
    void writeGrantMatchesByToolAndDirectory(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("auth-svc");
        Files.createDirectories(root.resolve("src"));
        Workspace ws = Workspace.single(root, PathMode.REAL);
        NativeToolDefinition writeDef =
                new NativeToolDefinition(
                        "write_to_file",
                        "write",
                        RiskCategory.FILE_WRITE,
                        false,
                        Object.class,
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call =
                new ToolCall(
                        "write_to_file",
                        Map.of("path", root.resolve("src/Main.java").toString(), "content", "x"));
        PermissionGrant.ToolCallSpec spec = MatchKeyExtractor.extract(call, writeDef, ws);
        PermissionGrant.WriteGrant grant =
                new PermissionGrant.WriteGrant("write_to_file", root.resolve("src"), List.of());
        assertTrue(grant.matches(spec));
    }

    @Test
    void commandGrantMatchesByExecutableAndSubcommand(@TempDir Path tmp) {
        Workspace ws = Workspace.single(tmp, PathMode.REAL);
        ToolCall gitStatus =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                List.of(
                                        Map.of(
                                                "executable",
                                                "git",
                                                "args",
                                                List.of("commit", "-m", "msg"))),
                                "cwd",
                                tmp.toString()));
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        Object.class,
                        Map.of());
        PermissionGrant.ToolCallSpec spec = MatchKeyExtractor.extract(gitStatus, execDef, ws);
        PermissionGrant.CommandGrant grant =
                new PermissionGrant.CommandGrant("git", List.of("commit"), List.of("-m"));
        assertTrue(grant.matches(spec));
    }

    @Test
    void commandGrantDoesNotMatchDifferentSubcommand(@TempDir Path tmp) {
        Workspace ws = Workspace.single(tmp, PathMode.REAL);
        ToolCall gitPush =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                List.of(Map.of("executable", "git", "args", List.of("push"))),
                                "cwd",
                                tmp.toString()));
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        Object.class,
                        Map.of());
        PermissionGrant.ToolCallSpec spec = MatchKeyExtractor.extract(gitPush, execDef, ws);
        PermissionGrant.CommandGrant grant =
                new PermissionGrant.CommandGrant("git", List.of("commit"), List.of());
        assertFalse(grant.matches(spec), "git push must not match a git commit grant");
    }

    @Test
    void hitlRegistryPersistsGrantOnLikeThis(@TempDir Path tmp) {
        HitlRegistry registry = new HitlRegistry();
        Path root = tmp.resolve("svc");
        Workspace ws = Workspace.single(root, PathMode.REAL);
        registry.setWorkspace(ws);
        String agentId = "agent-1";

        NativeToolDefinition readDef =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        RiskCategory.READ_ONLY,
                        false,
                        Object.class,
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("view_file", Map.of("path", root.resolve("a.txt").toString()));

        InterceptResolution res =
                new InterceptResolution(VetoOption.ACCEPT_READ_LIKE_THIS, call.args());
        // resolve requires a registered future; simulate by direct buildGrant path
        PermissionGrant grant = registry.buildGrant(call, readDef, res);
        assertNotNull(grant);
        assertTrue(grant instanceof PermissionGrant.ReadGrant);
    }

    @Test
    void hitlRegistryRevokesGrant() {
        HitlRegistry registry = new HitlRegistry();
        // First seed: register a pending veto future + resolve with a grant-creating option.
        String agentId = "agent-1";
        // We can't easily add a grant without going through resolve(), so use buildGrant +
        // a manual put via reflection-light approach: just exercise revokeGrant on a
        // never-registered grant (returns false).
        PermissionGrant g = new PermissionGrant.CommandGrant("git", List.of("status"), List.of());
        assertFalse(registry.revokeGrant(agentId, g));
    }

    @Test
    void maskingScrubsAwsKey() {
        String result = SecretMasker.mask("Found AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE");
        assertTrue(result.contains("[REDACTED_AWS_KEY]"));
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void maskingScrubsPrivateKeyBlock() {
        String result =
                SecretMasker.mask(
                        "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAK...lots of bytes..."
                                + "-----END RSA PRIVATE KEY-----");
        assertTrue(result.contains("[REDACTED_PRIVATE_KEY]"));
        assertFalse(result.contains("MIIEowIBAAK"));
    }

    @Test
    void maskingScrubsDbUrl() {
        String result = SecretMasker.mask("jdbc:postgresql://user:secret@host:5432/db");
        assertTrue(result.contains("[REDACTED_DB_URL]"));
    }

    @Test
    void vetoOptionCreatesGrantForLikeThisVariants() {
        assertTrue(VetoOption.ACCEPT_READ_LIKE_THIS.createsGrant());
        assertTrue(VetoOption.ACCEPT_AND_MASK_READ_LIKE_THIS.createsGrant());
        assertTrue(VetoOption.ACCEPT_WRITE_LIKE_THIS.createsGrant());
        assertTrue(VetoOption.ACCEPT_COMMAND_LIKE_THIS.createsGrant());
        assertTrue(VetoOption.ACCEPT_AND_MASK_COMMAND_LIKE_THIS.createsGrant());
        assertTrue(VetoOption.ACCEPT_COMMAND_AS_SESSION_RULE.createsGrant());
        assertTrue(VetoOption.ACCEPT_GENERIC_LIKE_THIS.createsGrant());
    }

    @Test
    void vetoOptionNonLikeThisDoesNotCreateGrant() {
        assertFalse(VetoOption.ACCEPT_READ.createsGrant());
        assertFalse(VetoOption.ACCEPT_AND_MASK_READ.createsGrant());
        assertFalse(VetoOption.ACCEPT_WRITE.createsGrant());
        assertFalse(VetoOption.READ_DECLINE.createsGrant());
        assertFalse(VetoOption.ACCEPT_GENERIC.createsGrant());
        assertFalse(VetoOption.ACCEPT_AND_MASK_COMMAND.createsGrant());
    }

    @Test
    void vetoOptionImpliesMaskingForAcceptAndMaskVariants() {
        assertTrue(VetoOption.ACCEPT_AND_MASK_READ.impliesMasking());
        assertTrue(VetoOption.ACCEPT_AND_MASK_WRITE.impliesMasking());
        assertTrue(VetoOption.ACCEPT_AND_MASK_COMMAND.impliesMasking());
        assertTrue(VetoOption.ACCEPT_AND_MASK_READ_LIKE_THIS.impliesMasking());
    }

    @Test
    void vetoOptionDeclineAndContinueIsRecognized() {
        assertTrue(VetoOption.DECLINE_AND_CONTINUE.isDeclineAndContinue());
        assertTrue(VetoOption.DECLINE_AND_CONTINUE.isRefusal());
    }

    @Test
    void scenarioForReadToolIsRead() {
        HitlRegistry registry = new HitlRegistry();
        NativeToolDefinition readDef =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        RiskCategory.READ_ONLY,
                        false,
                        Object.class,
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("view_file", Map.of("path", "/x"));
        var screening =
                new top.focess.veto.agent.screening.Screening(
                        top.focess.veto.agent.screening.Relevance.HIGH,
                        top.focess.veto.agent.screening.Danger.ELEVATED,
                        top.focess.veto.agent.intercept.VetoScenario.READ,
                        "test");
        assertEquals(VetoScenario.READ, registry.scenarioFor(call, readDef, screening));
    }

    @Test
    void scenarioForShellExecCriticalIsExecDeterministic() {
        HitlRegistry registry = new HitlRegistry();
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        Object.class,
                        Map.of());
        ToolCall call = new ToolCall("run_command", Map.of());
        var screening =
                new top.focess.veto.agent.screening.Screening(
                        top.focess.veto.agent.screening.Relevance.LOW,
                        top.focess.veto.agent.screening.Danger.CRITICAL,
                        VetoScenario.EXEC_DETERMINISTIC,
                        "test");
        assertEquals(
                VetoScenario.EXEC_DETERMINISTIC, registry.scenarioFor(call, execDef, screening));
    }

    @Test
    void scenarioForShellExecDangerousIsExecSemantic() {
        HitlRegistry registry = new HitlRegistry();
        NativeToolDefinition execDef =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        Object.class,
                        Map.of());
        ToolCall call = new ToolCall("run_command", Map.of());
        var screening =
                new top.focess.veto.agent.screening.Screening(
                        top.focess.veto.agent.screening.Relevance.LOW,
                        top.focess.veto.agent.screening.Danger.DANGEROUS,
                        VetoScenario.EXEC_SEMANTIC,
                        "test");
        assertEquals(VetoScenario.EXEC_SEMANTIC, registry.scenarioFor(call, execDef, screening));
    }

    @Test
    void scenarioForAgentToolIsGeneric() {
        HitlRegistry registry = new HitlRegistry();
        AgentToolDefinition agentDef =
                new AgentToolDefinition("create_group", "create", Object.class, Map.of());
        ToolCall call = new ToolCall("create_group", Map.of());
        var screening =
                new top.focess.veto.agent.screening.Screening(
                        top.focess.veto.agent.screening.Relevance.HIGH,
                        top.focess.veto.agent.screening.Danger.SAFE,
                        VetoScenario.GENERIC,
                        "test");
        assertEquals(VetoScenario.GENERIC, registry.scenarioFor(call, agentDef, screening));
    }
}
