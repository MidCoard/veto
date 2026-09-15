package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.llm.core.*;

class ContextUsageTrackerTest {
    @Test
    void persistsReportedCacheCountsAndKeepsMissingOrInvalidCountsUnknown() {
        var tracker = new ContextUsageTracker();
        var req = request("system", List.of(ChatMessage.user("one")));
        var known = tracker.measure(req, new LlmSystemUsage.Usage(1000, 5, 800L, 100L));
        var persisted = RecordUsage.add(TurnRecord.userPrompt(1, "one"), known);
        assertTrue(persisted.payload().get("llmUsage") instanceof List<?>);
        assertEquals(800L, known.get("cacheReadInputTokens"));
        assertEquals(100L, known.get("cacheCreationInputTokens"));
        assertFalse(
                tracker.measure(req, new LlmSystemUsage.Usage(1000, 5))
                        .containsKey("cacheReadInputTokens"));
        assertEquals(
                0L,
                tracker.measure(req, new LlmSystemUsage.Usage(1000, 5, 0L, null))
                        .get("cacheReadInputTokens"));
        assertFalse(
                tracker.measure(req, new LlmSystemUsage.Usage(1000, 5, 900L, 200L))
                        .containsKey("cacheReadInputTokens"));
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
                true,
                tracker.measure(first, new LlmSystemUsage.Usage(100, 10)).get("baselineReset"));
        var next =
                request("system", List.of(ChatMessage.user("one"), ChatMessage.assistant("two")));
        var measurement = tracker.measure(next, new LlmSystemUsage.Usage(120, 5));
        assertEquals(20L, measurement.get("contextDeltaTokens"));
        assertEquals(10L, measurement.get("inputDeltaTokens"));
        assertEquals("request_difference", measurement.get("inputDeltaSource"));
        assertFalse(
                tracker.measure(next, new LlmSystemUsage.Usage(120, 5))
                        .containsKey("inputDeltaTokens"));
        assertEquals(1, measurement.get("appendedMessages"));
        assertEquals(
                0L,
                tracker.measure(next, new LlmSystemUsage.Usage(120, 5)).get("contextDeltaTokens"));
        assertEquals(
                true,
                tracker.measure(
                                request("changed", next.messages()),
                                new LlmSystemUsage.Usage(90, 2))
                        .get("baselineReset"));
        tracker.reset();
        assertFalse(
                tracker.measure(next, new LlmSystemUsage.Usage(120, 5))
                        .containsKey("contextDeltaTokens"));
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
                -1L,
                tracker.measure(next, new LlmSystemUsage.Usage(109, 5)).get("inputDeltaTokens"));
        var correction =
                request(
                        "system",
                        List.of(
                                ChatMessage.user("one"),
                                ChatMessage.assistant("answer"),
                                ChatMessage.user("two"),
                                ChatMessage.user("correct")));
        assertEquals(
                7L,
                tracker.measure(correction, new LlmSystemUsage.Usage(116, 2))
                        .get("inputDeltaTokens"));
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
        assertEquals(false, measured.get("baselineReset"));
        assertEquals(29L, measured.get("inputDeltaTokens"));
        assertEquals(57L, measured.get("subtractedOutputTokens"));
        assertEquals(32724L, measured.get("inputTokens"));
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
                5L,
                tracker.measure(next, new LlmSystemUsage.Usage(115, 3)).get("inputDeltaTokens"));
        assertEquals(
                true,
                tracker.measure(
                                request("changed", next.messages()),
                                new LlmSystemUsage.Usage(130, 3))
                        .get("baselineReset"));
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
