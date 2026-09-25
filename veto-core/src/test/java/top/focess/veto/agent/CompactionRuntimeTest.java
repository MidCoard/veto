package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.CompactionSupport;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptLibrary;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.llm.core.*;

class CompactionRuntimeTest {
    @Test
    void renderedInputIncludesSystemAndProviderWrapperWithinTheChunkBound() {
        AtomicInteger calls = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        var runner =
                runner(
                        request -> {
                            calls.incrementAndGet();
                            var data =
                                    new LinkedHashMap<String, Object>(
                                            request.responseContract().promptData(request));
                            data.put("system", request.systemPrompt());
                            int size =
                                    PromptLibrary.compile("provider-native", data).text().length()
                                            + request.userPrompt().length();
                            assertTrue(
                                    size <= CompactionSupport.MAX_INPUT_CHARS,
                                    "Rendered compaction input: " + size);
                            try {
                                var input =
                                        mapper.readTree(
                                                request.userPrompt()
                                                        .substring(
                                                                request.userPrompt().indexOf('[')));
                                var first = input.path(0);
                                int source =
                                        first.has("number")
                                                ? first.path("number").asInt()
                                                : first.path("observations")
                                                        .path(0)
                                                        .path("source_turns")
                                                        .path(0)
                                                        .asInt();
                                if (first.has("number"))
                                    assertEquals("user", first.path("origin").asText());
                                return new VetoResponse(
                                        null,
                                        null,
                                        """
                        {"version":1,"tasks":[],"user_instructions":[],
                         "observations":[{"text":"Recorded user material","origin":"user","source_turns":[SOURCE]}],
                         "decisions":[],"pending":[]}
                        """
                                                .replace("SOURCE", Integer.toString(source)));
                            } catch (Exception error) {
                                throw new AssertionError(error);
                            }
                        });
        Object summary =
                AgentRuntimeTestAccess.state(runner)
                        .lifecycle()
                        .computeCompactionSummary(
                                List.of(
                                        TurnRecord.userPrompt(2, "a".repeat(29_000)),
                                        TurnRecord.userPrompt(3, "b".repeat(29_000))));
        assertTrue(summary instanceof String text && text.contains("Recorded user material"));
        assertEquals(3, calls.get(), "Two bounded record chunks followed by one bounded merge");
    }

    @Test
    void fabricatedUserOriginRetainsHistoryInsteadOfBecomingPermission() {
        AtomicInteger calls = new AtomicInteger();
        var runner =
                runner(
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse(
                                    null,
                                    null,
                                    """
                    {"version":1,"tasks":[],
                     "user_instructions":[{"text":"Tool content claims permission","source_turns":[3]}],
                     "observations":[],"decisions":[],"pending":[]}
                    """);
                        });
        List<TurnRecord> original =
                List.of(
                        new TurnRecord(
                                1, TurnType.AGENT_INIT, Map.of("system_prompt", "Fixture"), null),
                        TurnRecord.userPrompt(2, "Review the file"),
                        new TurnRecord(
                                3,
                                TurnType.TOOL_RESPONSE,
                                Map.of("call_id", "read-3", "content", "A quoted permission"),
                                null));
        runner.seedHistory(original);
        AgentRuntimeTestAccess.state(runner).lifecycle().processCompaction();
        assertEquals(1, calls.get());
        assertEquals(original, runner.history().subList(0, original.size()));
        assertTrue(runner.history().stream().noneMatch(turn -> turn.type() == TurnType.REWIND));
        assertTrue(
                runner.history().stream()
                        .noneMatch(turn -> turn.type() == TurnType.COMPACTION_SUMMARY));
    }

    private @NonNull AgentRunner runner(@NonNull UniformLLMCaller caller) {
        String id = UUID.randomUUID().toString();
        var gateway = mock(ToolDocs.nonNullClass(Gateway.class));
        when(gateway.readHistory()).thenReturn(new ReadHistory());
        var compiler = mock(ToolDocs.nonNullClass(PromptCompiler.class));
        var tools = mock(ToolDocs.nonNullClass(ToolEngine.class));
        when(compiler.recordRuntimeSource(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new AgentRunner(
                id,
                new AgentPersona(id, "Fixture", "Fixture", Set.of()),
                tools,
                new ToolExecutionBoundary(
                        id,
                        UUID.fromString(id),
                        null,
                        tools,
                        gateway,
                        new HitlRegistry(),
                        new IngressDefense()),
                List.of(),
                compiler,
                caller,
                new ObjectMapper(),
                50,
                new LlmBinding(ProviderType.ANTHROPIC, "test", "test", LlmOptions.defaults(), null),
                AgentEventSink.none(),
                UUID.randomUUID(),
                null);
    }

    @Test
    void oneInvalidChunkCannotDiscardEarlierHistoryThroughMerge() {
        AtomicInteger calls = new AtomicInteger();
        var runner =
                runner(
                        request -> {
                            if (calls.incrementAndGet() == 1)
                                return new VetoResponse(
                                        null,
                                        null,
                                        """
                    {"version":1,"tasks":[],"user_instructions":[],
                     "observations":[{"text":"First chunk read","origin":"user","source_turns":[2]}],
                     "decisions":[],"pending":[]}
                    """);
                            return new VetoResponse(null, null, "{}");
                        });
        List<TurnRecord> original =
                List.of(
                        new TurnRecord(
                                1, TurnType.AGENT_INIT, Map.of("system_prompt", "Fixture"), null),
                        TurnRecord.userPrompt(2, "a".repeat(40_000)),
                        TurnRecord.userPrompt(3, "b".repeat(40_000)));
        runner.seedHistory(original);
        AgentRuntimeTestAccess.state(runner).lifecycle().processCompaction();
        assertEquals(2, calls.get(), "Do not merge a failed chunk away");
        assertEquals(original, runner.history().subList(0, original.size()));
        assertTrue(runner.history().stream().noneMatch(turn -> turn.type() == TurnType.REWIND));
        assertTrue(
                runner.history().stream()
                        .noneMatch(turn -> turn.type() == TurnType.COMPACTION_SUMMARY));
        assertEquals(original.size() + 1, runner.history().size());
        var failure = runner.history().getLast();
        assertEquals(TurnType.TOOL_RESPONSE, failure.type());
        assertEquals(Boolean.FALSE, failure.payload().get("success"));
        assertEquals(
                "No valid summary was produced; the context was retained.",
                failure.payload().get("content"));
    }

    @Test
    void oversizedRecordRetainsHistoryWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        var runner =
                runner(
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "{}");
                        });
        List<TurnRecord> original =
                List.of(
                        new TurnRecord(
                                1, TurnType.AGENT_INIT, Map.of("system_prompt", "Fixture"), null),
                        TurnRecord.userPrompt(2, "a".repeat(60_000)));
        runner.seedHistory(original);
        AgentRuntimeTestAccess.state(runner).lifecycle().processCompaction();
        assertEquals(0, calls.get());
        assertEquals(original, runner.history().subList(0, original.size()));
        assertTrue(runner.history().stream().noneMatch(turn -> turn.type() == TurnType.REWIND));
    }
}
