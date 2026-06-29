package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.mcp.McpToolResult;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.veto.LlamaCppBridge;

/**
 * Integration test for the {@link SemanticMasker}-into-{@link IngressDefense} wiring: a risky
 * read/exec observation must be run through the SLM semantic masker (the advisory layer over the
 * deterministic {@link SecretMasker} floor), and the deterministic redaction must still apply
 * regardless of SLM availability.
 */
class IngressDefenseMaskingTest {

    private static NativeToolDefinition readToolDef() {
        return new NativeToolDefinition(
                "read_file",
                "Read a file",
                RiskCategory.READ_ONLY,
                false,
                Void.class,
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    private static McpToolResult result(String content) {
        return new McpToolResult("read_file", "c1", true, content);
    }

    private static ToolCall call() {
        return new ToolCall("read_file", Map.of("path", "/tmp/x"), "c1");
    }

    @Test
    void riskyObservationConsultsSlmSemanticMasker() {
        // A SemanticMasker backed by an available SLM that rates the observation "high" risk.
        LlamaCppBridge bridge = mock(LlamaCppBridge.class);
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.infer(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("{\"risk\":\"high\"}"));
        SemanticMasker masker = new SemanticMasker(bridge);
        IngressDefense defense = new IngressDefense(masker);

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
        // The no-arg constructor (used by existing tests + when no SLM is configured) must keep
        // applying the deterministic SecretMasker floor — the SLM is strictly advisory.
        IngressDefense defense = new IngressDefense();

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
