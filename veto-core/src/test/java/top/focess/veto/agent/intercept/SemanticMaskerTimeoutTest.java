package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.veto.LlamaCppBridge;

/**
 * Regression test for the SLM-timeout fix in SemanticMasker. The previous code called {@code
 * bridge.infer(...).get()} without a timeout — a wedged SLM would block the calling virtual thread
 * indefinitely. The fix bounds the wait via {@link SemanticMasker#SLM_TIMEOUT_MS}.
 */
class SemanticMaskerTimeoutTest {

    private static @NonNull SemanticMasker masker(@NonNull LlamaCppBridge bridge) {
        var plugins = mock(PluginManager.class);
        when(plugins.applyObservationMiddleware(anyString()))
                .thenAnswer(
                        invocation -> {
                            String text = invocation.getArgument(0, String.class);
                            if (text == null)
                                throw new IllegalArgumentException("Missing observation");
                            return text.replace("ABCD", "[TEST_PROTECTED]");
                        });
        return new SemanticMasker(bridge, plugins);
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
                Void.class,
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    @Test
    void slmHangStillAppliesObservationMiddlewareWithinTimeout() throws Exception {
        // Bridge stub whose infer() returns a future that never completes — simulates a wedged
        // SLM / native crash. The masker must time out, apply observation middleware, and not
        // block the caller beyond the configured SLM_TIMEOUT_MS.
        LlamaCppBridge bridge = mock(LlamaCppBridge.class);
        when(bridge.isAvailable()).thenReturn(true);
        var pending = new CompletableFuture<String>();
        when(bridge.infer(anyString(), anyString())).thenReturn(pending);
        SemanticMasker masker = masker(bridge);

        long start = System.currentTimeMillis();
        SemanticMasker.MaskResult result =
                masker.maskWithSignal(
                        "exfiltrating api_key=ABCD",
                        new ToolCall("read_file", Map.of("path", "/tmp/x"), "c1"),
                        readToolDef());
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(result);
        assertNotNull(result.masked());
        assertNull(result.highRisk(), "no SLM verdict (hang) → no high-risk signal");
        assertTrue(
                result.masked().contains("[TEST_PROTECTED]"),
                "must still apply observation middleware on timeout");
        assertTrue(
                elapsed < SemanticMasker.SLM_TIMEOUT_MS + 1_000L,
                "masker must not block beyond the SLM timeout (took " + elapsed + "ms)");
        assertTrue(pending.isCancelled(), "timed-out inference must release its HTTP request");
    }

    @Test
    void fastSlmVerdictStillApplies() {
        LlamaCppBridge bridge = mock(LlamaCppBridge.class);
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.infer(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("{\"risk\":\"high\"}"));
        SemanticMasker masker = masker(bridge);
        SemanticMasker.MaskResult result =
                masker.maskWithSignal(
                        "exfiltrating api_key=ABCD",
                        new ToolCall("read_file", Map.of("path", "/tmp/x"), "c1"),
                        readToolDef());
        assertTrue(result.highRisk() != null, "high verdict must surface a HighRiskSignal");
    }

    @Test
    void slmUnavailableStillAppliesObservationMiddleware() {
        LlamaCppBridge bridge = mock(LlamaCppBridge.class);
        when(bridge.isAvailable()).thenReturn(false);
        SemanticMasker masker = masker(bridge);
        SemanticMasker.MaskResult result =
                masker.maskWithSignal(
                        "exfiltrating api_key=ABCD",
                        new ToolCall("read_file", Map.of("path", "/tmp/x"), "c1"),
                        readToolDef());
        assertNull(result.highRisk());
        assertTrue(result.masked().contains("[TEST_PROTECTED]"));
    }
}
