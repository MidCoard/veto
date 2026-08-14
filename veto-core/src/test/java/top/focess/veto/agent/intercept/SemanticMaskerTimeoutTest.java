package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.veto.LlamaCppBridge;

/**
 * Regression test for the SLM-timeout fix in SemanticMasker. The previous code called {@code
 * bridge.infer(...).get()} without a timeout — a wedged SLM would block the calling virtual thread
 * indefinitely. The fix bounds the wait via {@link SemanticMasker#SLM_TIMEOUT_MS}.
 */
class SemanticMaskerTimeoutTest {

    @SuppressWarnings("type.arguments.not.inferred")
    private static @NonNull NativeToolDefinition readToolDef() {
        return new NativeToolDefinition(
                "read_file",
                "Read a file",
                RiskCategory.READ_ONLY,
                false,
                ToolDocs.nonNullClass(Void.class),
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    @Test
    void slmHangFallsBackToDeterministicMaskingWithinTimeout() throws Exception {
        // Bridge stub whose infer() returns a future that never completes — simulates a wedged
        // SLM / native crash. The masker must time out, fall back to SecretMasker, and not
        // block the caller beyond the configured SLM_TIMEOUT_MS.
        LlamaCppBridge bridge = mock(ToolDocs.nonNullClass(LlamaCppBridge.class));
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.infer(anyString(), anyString())).thenReturn(new CompletableFuture<>());
        SemanticMasker masker = new SemanticMasker(bridge);

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
                result.masked().contains("[REDACTED_"),
                "must still apply deterministic SecretMasker as fallback on timeout");
        assertTrue(
                elapsed < SemanticMasker.SLM_TIMEOUT_MS + 1_000L,
                "masker must not block beyond the SLM timeout (took " + elapsed + "ms)");
    }

    @Test
    void fastSlmVerdictStillApplies() {
        LlamaCppBridge bridge = mock(ToolDocs.nonNullClass(LlamaCppBridge.class));
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.infer(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("{\"risk\":\"high\"}"));
        SemanticMasker masker = new SemanticMasker(bridge);
        SemanticMasker.MaskResult result =
                masker.maskWithSignal(
                        "exfiltrating api_key=ABCD",
                        new ToolCall("read_file", Map.of("path", "/tmp/x"), "c1"),
                        readToolDef());
        assertTrue(result.highRisk() != null, "high verdict must surface a HighRiskSignal");
    }

    @Test
    void slmUnavailableFallsBackToDeterministic() {
        LlamaCppBridge bridge = mock(ToolDocs.nonNullClass(LlamaCppBridge.class));
        when(bridge.isAvailable()).thenReturn(false);
        SemanticMasker masker = new SemanticMasker(bridge);
        SemanticMasker.MaskResult result =
                masker.maskWithSignal(
                        "exfiltrating api_key=ABCD",
                        new ToolCall("read_file", Map.of("path", "/tmp/x"), "c1"),
                        readToolDef());
        assertNull(result.highRisk());
        assertTrue(result.masked().contains("[REDACTED_"));
    }
}
