package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.builtin.workspace.ViewFileTool;
import top.focess.veto.plugin.runtime.PluginLifecycleEvents;
import top.focess.veto.plugin.runtime.PluginManager;
import top.focess.veto.plugin.runtime.PluginTestSupport;
import top.focess.veto.veto.LlamaCppBridge;

/**
 * Integration test for the {@link SemanticMasker}-into-{@link IngressDefense} wiring: a risky
 * read/exec observation must be run through the SLM semantic masker (the advisory layer over the
 * plugin-provided {@code veto:observation-middleware} floor), and the redaction must still apply
 * regardless of SLM availability. The scoped SECRET_REF capture lives in the secret-protection
 * plugin, exercised here through the catalog's typed protection points.
 */
class IngressDefenseMaskingTest {
    @SuppressWarnings("nullness:initialization.static.field.uninitialized") // Set in @BeforeAll.
    private static @NonNull PluginManager plugins;

    @BeforeAll
    static void startPlugins() throws IOException {
        plugins = PluginTestSupport.manager();
    }

    @AfterAll
    static void stopPlugins() {
        plugins.close();
    }

    @Test
    void protectedFileReferencesSurviveMaskingOnlyWithinTheirLiveScope() throws Exception {
        var scope = new TextProtection.Scope("owner", "session", "agent");
        String captured =
                PluginTestSupport.protect(
                        plugins,
                        StandardContributionPoints.FILE_PROTECTION,
                        scope,
                        "file",
                        "token=synthetic-token");
        var definition = ToolSchemaCompiler.compileNative(new ViewFileTool());
        var fileCall = new ToolCall("view_file", Map.of("absolutePath", "/fixture"), "file-call");
        var fileResult =
                ToolResult.success("view_file", "file-call", captured + "\npassword=extra-secret");
        LlamaCppBridge bridge = mock(ToolDocs.nonNullClass(LlamaCppBridge.class));
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.infer(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("{\"risk\":\"high\"}"));
        var defense =
                new IngressDefense(
                        new SemanticMasker(bridge, PluginTestSupport.providerOf(plugins)),
                        PluginTestSupport.providerOf(plugins));
        String observed =
                PluginTestSupport.protect(
                        plugins,
                        StandardContributionPoints.FILE_OBSERVATION,
                        scope,
                        "file",
                        fileResult.content());
        String masked = defense.frameProtectedFile(fileCall, definition, fileResult, observed);
        assertTrue(masked.startsWith(captured + "\n"), masked);
        assertFalse(masked.contains("extra-secret"));
        assertFalse(masked.contains("synthetic-token"));
        verify(bridge, times(1)).infer(anyString(), anyString());
        // Outside the live scope the reference is unavailable.
        assertThrows(
                IllegalStateException.class,
                () ->
                        PluginTestSupport.protect(
                                plugins,
                                StandardContributionPoints.FILE_OBSERVATION,
                                new TextProtection.Scope("owner", "session", "other-agent"),
                                "file",
                                fileResult.content()));
        new PluginLifecycleEvents(plugins).ownerClosed("owner");
        assertThrows(
                IllegalStateException.class,
                () ->
                        PluginTestSupport.protect(
                                plugins,
                                StandardContributionPoints.FILE_OBSERVATION,
                                scope,
                                "file",
                                fileResult.content()));
    }

    @SuppressWarnings("type.arguments.not.inferred")
    private static @NonNull NativeToolDefinition readToolDef() {
        return new NativeToolDefinition(
                "read_file",
                "Read a file",
                ToolCapability.WORKSPACE_READ,
                Danger.SAFE,
                false,
                Object.class,
                ToolDocs.nonNullClass(Void.class),
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    private static @NonNull ToolResult result(@NonNull String content) {
        return ToolResult.success("read_file", "c1", content);
    }

    private static @NonNull ToolCall call() {
        return new ToolCall("read_file", Map.of("path", "/tmp/x"), "c1");
    }

    @Test
    void riskyObservationConsultsSlmSemanticMasker() {
        // A SemanticMasker backed by an available SLM that rates the observation "high" risk.
        LlamaCppBridge bridge = mock(ToolDocs.nonNullClass(LlamaCppBridge.class));
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.infer(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("{\"risk\":\"high\"}"));
        SemanticMasker masker = new SemanticMasker(bridge, PluginTestSupport.providerOf(plugins));
        IngressDefense defense = new IngressDefense(masker, PluginTestSupport.providerOf(plugins));

        String framed =
                defense.maskAndFrame(
                        call(),
                        readToolDef(),
                        result("exfiltrating api_key=ABCD"),
                        true,
                        new ReadHistory());

        // The deterministic floor still applies (the SLM verdict never bypasses redaction).
        assertTrue(
                framed.contains("[REDACTED_"), "deterministic redaction still applies: " + framed);
        // And the SLM was actually consulted — proving the semantic-masker path is wired in.
        verify(bridge).infer(anyString(), anyString());
    }

    @Test
    void noSlmStillAppliesDeterministicMasking() {
        // Without the SLM semantic layer the plugin-provided deterministic floor still applies.
        IngressDefense defense = new IngressDefense(null, PluginTestSupport.providerOf(plugins));

        String framed =
                defense.maskAndFrame(
                        call(),
                        readToolDef(),
                        result("exfiltrating api_key=ABCD"),
                        true,
                        new ReadHistory());

        assertTrue(framed.contains("[REDACTED_"), "deterministic redaction applies without an SLM");
    }
}
