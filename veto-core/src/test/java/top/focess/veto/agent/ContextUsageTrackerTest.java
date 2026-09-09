package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.llm.core.*;

class ContextUsageTrackerTest {
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
