package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.veto.GBNFGrammarEngine;
import top.focess.veto.veto.LlamaCppBridge;
import top.focess.veto.veto.VetoGatewayConfiguration;

/** Tests for the Part 3.2 local-SLM-backed relevance provider. */
class LocalSlmRelevanceProviderTest {

    /** A test double for LlamaCppBridge that returns canned responses. */
    static class FakeBridge extends LlamaCppBridge {
        private final boolean available;
        private final @NonNull String cannedResponse;

        FakeBridge(boolean available, @NonNull String cannedResponse) {
            super(
                    mock(ToolDocs.nonNullClass(VetoGatewayConfiguration.class)),
                    mock(ToolDocs.nonNullClass(GBNFGrammarEngine.class)));
            this.available = available;
            this.cannedResponse = cannedResponse;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public @NonNull CompletableFuture<String> infer(
                @NonNull String prompt, @NonNull String grammarName) {
            return CompletableFuture.completedFuture(cannedResponse);
        }
    }

    @Test
    void slmUnavailableFallsBackToHigh() {
        LocalSlmRelevanceProvider provider =
                new LocalSlmRelevanceProvider(new FakeBridge(false, ""));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        RiskCategory.READ_ONLY,
                        false,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("view_file", Map.of("path", "/a/b"));
        assertEquals(
                Relevance.HIGH,
                provider.relevance(call, def, "looking at file b"),
                "Unreachable SLM → fallback HIGH");
    }

    @Test
    void slmParsesHigh() {
        LocalSlmRelevanceProvider provider =
                new LocalSlmRelevanceProvider(new FakeBridge(true, "HIGH"));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        RiskCategory.READ_ONLY,
                        false,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("view_file", Map.of("path", "/a/b"));
        assertEquals(Relevance.HIGH, provider.relevance(call, def, "reading b"));
    }

    @Test
    void slmParsesMedium() {
        LocalSlmRelevanceProvider provider =
                new LocalSlmRelevanceProvider(new FakeBridge(true, "MEDIUM"));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "write_to_file",
                        "write",
                        RiskCategory.FILE_WRITE,
                        false,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("write_to_file", Map.of("path", "/x", "content", "y"));
        assertEquals(Relevance.MEDIUM, provider.relevance(call, def, "writing a side file"));
    }

    @Test
    void slmParsesLow() {
        LocalSlmRelevanceProvider provider =
                new LocalSlmRelevanceProvider(new FakeBridge(true, "LOW"));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        RiskCategory.SHELL_EXEC,
                        false,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of());
        ToolCall call = new ToolCall("run_command", Map.of("commands", List.of()));
        // Note: Real run_command would route via sandbox. This is just exercising the relevance
        // test.
        assertEquals(Relevance.LOW, provider.relevance(call, def, "scanning network"));
    }

    @Test
    void slmUnparseableResponseFallsBackToHigh() {
        LocalSlmRelevanceProvider provider =
                new LocalSlmRelevanceProvider(new FakeBridge(true, "I'm not sure"));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        RiskCategory.READ_ONLY,
                        false,
                        Object.class,
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("view_file", Map.of("path", "/a/b"));
        assertEquals(Relevance.HIGH, provider.relevance(call, def, "looking at b"));
    }
}
