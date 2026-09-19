package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import top.focess.veto.llm.core.*;

import java.util.List;

class ContextUsageTrackerTest {
    @Test
    void persistsReportedCacheCountsAndKeepsMissingOrInvalidCountsUnknown() {
        var tracker = new ContextUsageTracker();
        var req = request("system", List.of(ChatMessage.user("one")));
        var known = tracker.measure(req, new LlmSystemUsage.Usage(1000, 5, 800L, 100L));
        var persisted = RecordUsage.add(TurnRecord.userPrompt(1, "one"), known);
        assertTrue(persisted.payload().get("llmUsage") instanceof List<?>);
        assertEquals(Long.valueOf(800L), known.cacheReadInputTokens());
        assertEquals(Long.valueOf(100L), known.cacheCreationInputTokens());
        assertFalse(
                tracker.measure(req, new LlmSystemUsage.Usage(1000, 5)).cacheReadInputTokens()
                        != null);
        assertEquals(
                Long.valueOf(0L),
                tracker.measure(req, new LlmSystemUsage.Usage(1000, 5, 0L, null))
                        .cacheReadInputTokens());
        assertFalse(
                tracker.measure(req, new LlmSystemUsage.Usage(1000, 5, 900L, 200L))
                                .cacheReadInputTokens()
                        != null);
    }

    @Test
    void restoresMeasuredBaselineAcrossSerializationWithoutKeepingPromptText() throws Exception {
        var tracker = new ContextUsageTracker();
        var first = request("private-system", List.of(ChatMessage.user("private-input")));
        tracker.measure(first, new LlmSystemUsage.Usage(19291, 72));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var checkpoint = tracker.checkpoint();
        if (checkpoint == null) throw new AssertionError("Missing checkpoint");
        String serialized = mapper.writeValueAsString(checkpoint);
        assertFalse(serialized.contains("private"));
        var recovered = new ContextUsageTracker();
        recovered.restore(
                mapper.readValue(
                        serialized,
                        top.focess.veto.agent.tool.ToolDocs.nonNullClass(UsageCheckpoint.class)));
        var next =
                request(
                        "private-system",
                        List.of(
                                ChatMessage.user("private-input"),
                                ChatMessage.assistant("answer"),
                                ChatMessage.user("next")));
        var measured = recovered.measure(next, new LlmSystemUsage.Usage(19436, 67));
        assertFalse(measured.baselineReset());
        assertEquals(Long.valueOf(145), measured.contextDeltaTokens());
        assertEquals(Long.valueOf(73), measured.inputDeltaTokens());
        recovered.restore(
                mapper.readValue(
                        serialized,
                        top.focess.veto.agent.tool.ToolDocs.nonNullClass(UsageCheckpoint.class)));
        assertTrue(
                recovered
                        .measure(
                                request("changed-system", next.messages()),
                                new LlmSystemUsage.Usage(19436, 67))
                        .baselineReset());
    }

    private @NonNull VetoRequest request(
            @NonNull String system, @NonNull List<ChatMessage> messages) {
        return new VetoRequest(
                system,
                messages.getLast().content(),
                List.of(),
                ProviderType.DEEPSEEK,
                "test",
                "unused",
                LlmOptions.defaults(),
                messages,
                null,
                null);
    }

    @Test
    void measuresAnAppendAndResetsOnContentReplacement() {
        var tracker = new ContextUsageTracker();
        var first = request("system", List.of(ChatMessage.user("one")));
        assertEquals(
                true, tracker.measure(first, new LlmSystemUsage.Usage(100, 10)).baselineReset());
        var next =
                request("system", List.of(ChatMessage.user("one"), ChatMessage.assistant("two")));
        var measurement = tracker.measure(next, new LlmSystemUsage.Usage(120, 5));
        assertEquals(Long.valueOf(20L), measurement.contextDeltaTokens());
        assertEquals(Long.valueOf(10L), measurement.inputDeltaTokens());
        assertEquals("request_difference", measurement.inputDeltaSource());
        assertFalse(
                tracker.measure(next, new LlmSystemUsage.Usage(120, 5)).inputDeltaTokens() != null);
        assertEquals(Integer.valueOf(1), measurement.appendedMessages());
        assertEquals(
                Long.valueOf(0L),
                tracker.measure(next, new LlmSystemUsage.Usage(120, 5)).contextDeltaTokens());
        assertEquals(
                true,
                tracker.measure(
                                request("changed", next.messages()),
                                new LlmSystemUsage.Usage(90, 2))
                        .baselineReset());
        tracker.reset();
        assertFalse(
                tracker.measure(next, new LlmSystemUsage.Usage(120, 5)).contextDeltaTokens()
                        != null);
    }

    @Test
    void subtractsRetainedOutputAndKeepsNegativeDifferences() {
        var tracker = new ContextUsageTracker();
        var first = request("system", List.of(ChatMessage.user("one")));
        tracker.measure(first, new LlmSystemUsage.Usage(100, 10));
        var next =
                request(
                        "system",
                        List.of(
                                ChatMessage.user("one"),
                                ChatMessage.assistant("answer"),
                                ChatMessage.user("two")));
        assertEquals(
                Long.valueOf(-1L),
                tracker.measure(next, new LlmSystemUsage.Usage(109, 5)).inputDeltaTokens());
        var correction =
                request(
                        "system",
                        List.of(
                                ChatMessage.user("one"),
                                ChatMessage.assistant("answer"),
                                ChatMessage.user("two"),
                                ChatMessage.user("correct")));
        assertEquals(
                Long.valueOf(7L),
                tracker.measure(correction, new LlmSystemUsage.Usage(116, 2)).inputDeltaTokens());
    }

    @Test
    void measuresToolInputAfterDiscardingTwoEphemeralCorrections() {
        var tracker = new ContextUsageTracker();
        var original = request("system", List.of(ChatMessage.user("read file")));
        tracker.measure(original, new LlmSystemUsage.Usage(32638, 52));
        var input = tracker.baseline();
        tracker.measure(
                request(
                        "system",
                        List.of(ChatMessage.user("read file"), ChatMessage.user("fix protocol"))),
                new LlmSystemUsage.Usage(32682, 48));
        tracker.measure(
                request(
                        "system",
                        List.of(
                                ChatMessage.user("read file"),
                                ChatMessage.user("fix protocol"),
                                ChatMessage.user("fix JSON"))),
                new LlmSystemUsage.Usage(32815, 57));
        tracker.accept(input, tracker.baseline());
        var next =
                request(
                        "system",
                        List.of(
                                ChatMessage.user("read file"),
                                ChatMessage.assistant("accepted call"),
                                ChatMessage.user("file result")));
        var measured = tracker.measure(next, new LlmSystemUsage.Usage(32724, 6));
        assertEquals(false, measured.baselineReset());
        assertEquals(Long.valueOf(29L), measured.inputDeltaTokens());
        assertEquals(Long.valueOf(57L), measured.subtractedOutputTokens());
        assertEquals(Long.valueOf(32724L), measured.inputTokens());
    }

    @Test
    void usesSelectedCandidateOutputInsteadOfTheLastRejectedAttempt() {
        var tracker = new ContextUsageTracker();
        var original = request("system", List.of(ChatMessage.user("one")));
        tracker.measure(original, new LlmSystemUsage.Usage(100, 10));
        var candidate = tracker.baseline();
        tracker.measure(
                request("system", List.of(ChatMessage.user("one"), ChatMessage.user("fix"))),
                new LlmSystemUsage.Usage(120, 80));
        tracker.accept(candidate, candidate);
        var next =
                request(
                        "system",
                        List.of(
                                ChatMessage.user("one"),
                                ChatMessage.assistant("accepted"),
                                ChatMessage.user("two")));
        assertEquals(
                Long.valueOf(5L),
                tracker.measure(next, new LlmSystemUsage.Usage(115, 3)).inputDeltaTokens());
        assertEquals(
                true,
                tracker.measure(
                                request("changed", next.messages()),
                                new LlmSystemUsage.Usage(130, 3))
                        .baselineReset());
    }

    @Test
    void recordsEveryRetryAndClearsAtBoundary() {
        LlmSystemUsage.begin();
        LlmSystemUsage.set(100, 10);
        LlmSystemUsage.set(100, 20);
        assertEquals(2, LlmSystemUsage.snapshot().size());
        assertEquals(2, LlmSystemUsage.snapshot().size());
        assertEquals(
                List.of(new LlmSystemUsage.Usage(100, 10), new LlmSystemUsage.Usage(100, 20)),
                LlmSystemUsage.drain());
        assertTrue(LlmSystemUsage.drain().isEmpty());
    }
}
