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
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.veto.GBNFGrammarEngine;
import top.focess.veto.veto.LlamaCppBridge;
import top.focess.veto.veto.SlmConfiguration;

/** Tests for the local-SLM-backed relevance-and-danger provider. */
class LocalSlmScreeningProviderTest {

    /** A test double for LlamaCppBridge that returns canned responses. */
    static class FakeBridge extends LlamaCppBridge {
        private final boolean available;
        private final @NonNull String cannedResponse;
        private @NonNull String lastPrompt = "";

        FakeBridge(boolean available, @NonNull String cannedResponse) {
            super(
                    mock(ToolDocs.nonNullClass(SlmConfiguration.class)),
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
            lastPrompt = prompt;
            return CompletableFuture.completedFuture(cannedResponse);
        }

        @NonNull String lastPrompt() {
            return lastPrompt;
        }
    }

    @Test
    void slmUnavailableProducesNoFabricatedJudgment() {
        LocalSlmScreeningProvider provider =
                new LocalSlmScreeningProvider(new FakeBridge(false, ""));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        ToolCapability.WORKSPACE_READ,
                        Danger.SAFE,
                        false,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("view_file", Map.of("path", "/a/b"));
        assertTrue(provider.screen(call, def, "looking at file b").isEmpty());
    }

    @Test
    void slmParsesHigh() {
        FakeBridge bridge =
                new FakeBridge(
                        true,
                        "{\"relevance\":\"HIGH\",\"danger\":\"SAFE\",\"reason\":\"on task\"}");
        LocalSlmScreeningProvider provider = new LocalSlmScreeningProvider(bridge);
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        ToolCapability.WORKSPACE_READ,
                        Danger.SAFE,
                        false,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("view_file", Map.of("path", "/a/b"));
        assertEquals(
                Relevance.HIGH, provider.screen(call, def, "reading b").orElseThrow().relevance());
        assertTrue(bridge.lastPrompt().contains("SAFE = read-only"));
        assertTrue(bridge.lastPrompt().contains("CRITICAL = irreversible"));
    }

    @Test
    void slmReceivesProcessExecutionContext() {
        FakeBridge bridge =
                new FakeBridge(
                        true,
                        "{\"relevance\":\"HIGH\",\"danger\":\"SAFE\",\"reason\":\"on task\"}");
        LocalSlmScreeningProvider provider = new LocalSlmScreeningProvider(bridge);
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "input_task",
                        "input",
                        ToolCapability.TASK_CONTROL,
                        Danger.SAFE,
                        true,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of("content", ParamCategory.PROCESS_INPUT));
        ToolCall call = new ToolCall("input_task", Map.of("taskId", "bg-1", "content", "yes"));

        provider.screen(
                call,
                def,
                "answer the prompt",
                "send yes",
                "Target background process: executable=installer, networkAllowed=false");

        assertTrue(bridge.lastPrompt().contains("executable=installer"));
        assertTrue(bridge.lastPrompt().contains("networkAllowed=false"));
    }

    @Test
    void slmParsesMedium() {
        LocalSlmScreeningProvider provider =
                new LocalSlmScreeningProvider(
                        new FakeBridge(
                                true,
                                "{\"relevance\":\"MEDIUM\",\"danger\":\"ELEVATED\",\"reason\":\"weak"
                                        + " justification\"}"));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "write_to_file",
                        "write",
                        ToolCapability.WORKSPACE_WRITE,
                        Danger.ELEVATED,
                        false,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("write_to_file", Map.of("path", "/x", "content", "y"));
        SlmScreening screening = provider.screen(call, def, "writing a side file").orElseThrow();
        assertEquals(Relevance.MEDIUM, screening.relevance());
        assertEquals(Danger.ELEVATED, screening.danger());
    }

    @Test
    void slmParsesLow() {
        LocalSlmScreeningProvider provider =
                new LocalSlmScreeningProvider(
                        new FakeBridge(
                                true,
                                "{\"relevance\":\"LOW\",\"danger\":\"DANGEROUS\",\"reason\":\"unrelated"
                                        + " scan\"}"));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "run_command",
                        "exec",
                        ToolCapability.PROCESS_EXECUTION,
                        Danger.ELEVATED,
                        false,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of());
        ToolCall call = new ToolCall("run_command", Map.of("commands", List.of()));
        // Note: Real run_command would route via sandbox. This is just exercising the relevance
        // test.
        SlmScreening screening = provider.screen(call, def, "scanning network").orElseThrow();
        assertEquals(Relevance.LOW, screening.relevance());
        assertEquals(Danger.DANGEROUS, screening.danger());
    }

    @Test
    void slmUnparseableResponseProducesNoFabricatedJudgment() {
        LocalSlmScreeningProvider provider =
                new LocalSlmScreeningProvider(new FakeBridge(true, "I'm not sure"));
        NativeToolDefinition def =
                new NativeToolDefinition(
                        "view_file",
                        "read",
                        ToolCapability.WORKSPACE_READ,
                        Danger.SAFE,
                        false,
                        Object.class,
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        ToolCall call = new ToolCall("view_file", Map.of("path", "/a/b"));
        assertTrue(provider.screen(call, def, "looking at b").isEmpty());
    }
}
