package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.GatewayResult;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

@SuppressWarnings("initialization.field.uninitialized")
class GatewayScreeningTest {

    // JUnit's @TempDir may sit under a junction / 8.3 short-name (Windows: ADMINI~1) so its lexical
    // path differs from toRealPath(); the REAL-mode PathResolver canonicalizes candidates via
    // toRealPath(), so the root must be canonicalized to match (same accommodation as
    // DangerComputationTest#canonicalizeRoot / PathResolverTest). Without this, in-scope temp paths
    // are misclassified out-of-scope (CRITICAL) on Windows.
    @TempDir @NonNull Path root;

    @BeforeEach
    void canonicalizeRoot() throws Exception {
        root = root.toRealPath();
    }

    private @NonNull Gateway gateway() {
        Workspace ws = Workspace.single(root, PathMode.REAL);
        return new Gateway(
                ws,
                new DangerComputation(),
                new UnavailableSlmScreeningProvider(),
                DeployerPolicy.FULL_ACCESS,
                ProtectedSet.empty(),
                new ReadHistory());
    }

    private @NonNull NativeToolDefinition readDef() {
        return new NativeToolDefinition(
                "view_file",
                "read",
                RiskCategory.READ_ONLY,
                false,
                ToolDocs.nonNullClass(String.class),
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    private @NonNull NativeToolDefinition writeDef() {
        return new NativeToolDefinition(
                "write_to_file",
                "write",
                RiskCategory.FILE_WRITE,
                false,
                ToolDocs.nonNullClass(String.class),
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    @Test
    void agentToolEarlyRoutesToNotScreened() {
        AgentToolDefinition atd =
                new AgentToolDefinition(
                        "load_skill", "load", ToolDocs.nonNullClass(String.class), Map.of());
        GatewayResult r =
                gateway().screen(new ToolCall("load_skill", Map.of("skillName", "x")), atd);
        assertInstanceOf(ToolDocs.nonNullClass(GatewayResult.NotScreened.class), r);
    }

    @Test
    void inProjectReadScreensSafe() throws Exception {
        Files.createDirectories(root.resolve("src"));
        ToolCall call =
                new ToolCall("view_file", Map.of("path", root.resolve("src/Main.java").toString()));
        GatewayResult r = gateway().screen(call, readDef());
        assertInstanceOf(ToolDocs.nonNullClass(GatewayResult.Screened.class), r);
        Screening s = ((GatewayResult.Screened) r).screening();
        assertEquals(Danger.SAFE, s.danger());
        assertEquals(Relevance.HIGH, s.relevance());
        assertFalse(s.slmEvaluated());
    }

    @Test
    void advisoryModelCanRaiseButNeverLowerDeterministicDanger() throws Exception {
        Workspace ws = Workspace.single(root, PathMode.REAL);
        SlmScreeningProvider raisesDanger =
                (call, def, activeTask, thought) ->
                        java.util.Optional.of(
                                new SlmScreening(
                                        Relevance.LOW,
                                        Danger.DANGEROUS,
                                        "intent is unrelated and risky"));
        Gateway raises =
                new Gateway(
                        ws,
                        new DangerComputation(),
                        raisesDanger,
                        DeployerPolicy.FULL_ACCESS,
                        ProtectedSet.empty(),
                        new ReadHistory());
        ToolCall safeRead =
                new ToolCall("view_file", Map.of("path", root.resolve("README.md").toString()));
        Screening raised =
                ((GatewayResult.Screened) raises.screen(safeRead, readDef(), "scan secrets"))
                        .screening();
        assertEquals(Relevance.LOW, raised.relevance());
        assertEquals(Danger.DANGEROUS, raised.danger());
        assertTrue(raised.slmEvaluated());

        SlmScreeningProvider claimsSafe =
                (call, def, activeTask, thought) ->
                        java.util.Optional.of(
                                new SlmScreening(Relevance.HIGH, Danger.SAFE, "model claims safe"));
        Gateway deterministicFloor =
                new Gateway(
                        ws,
                        new DangerComputation(),
                        claimsSafe,
                        DeployerPolicy.SANDBOXED,
                        ProtectedSet.empty(),
                        new ReadHistory());
        ToolCall escapedRead =
                new ToolCall(
                        "view_file", Map.of("path", root.resolve("../../etc/passwd").toString()));
        Screening floored =
                ((GatewayResult.Screened) deterministicFloor.screen(escapedRead, readDef()))
                        .screening();
        assertEquals(Danger.CRITICAL, floored.danger());
    }

    @Test
    void outOfScopeReadScreensCritical() {
        Workspace ws = Workspace.single(root, PathMode.REAL);
        Gateway g =
                new Gateway(
                        ws,
                        new DangerComputation(),
                        new UnavailableSlmScreeningProvider(),
                        DeployerPolicy.SANDBOXED,
                        ProtectedSet.empty(),
                        new ReadHistory());
        ToolCall call =
                new ToolCall(
                        "view_file", Map.of("path", root.resolve("../../etc/passwd").toString()));
        GatewayResult r = g.screen(call, readDef());
        Screening s = ((GatewayResult.Screened) r).screening();
        assertEquals(Danger.CRITICAL, s.danger());
    }

    @Test
    void writeDriftProducesDriftResult() throws Exception {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "original");
        ReadHistory rh = new ReadHistory();
        rh.record(f.toString(), 8L, Files.getLastModifiedTime(f).toInstant(), "h1");
        Workspace ws = Workspace.single(root, PathMode.REAL);
        Gateway g =
                new Gateway(
                        ws,
                        new DangerComputation(),
                        new UnavailableSlmScreeningProvider(),
                        DeployerPolicy.FULL_ACCESS,
                        ProtectedSet.empty(),
                        rh);
        Files.writeString(f, "CHANGED"); // drift
        ToolCall call =
                new ToolCall("write_to_file", Map.of("path", f.toString(), "content", "new"));
        GatewayResult r = g.screen(call, writeDef());
        assertInstanceOf(ToolDocs.nonNullClass(GatewayResult.DriftResult.class), r);
    }
}
