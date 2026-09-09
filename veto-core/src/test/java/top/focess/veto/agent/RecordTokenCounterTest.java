package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.llm.core.*;

class RecordTokenCounterTest {
    private @NonNull VetoRequest request(@NonNull List<ChatMessage> messages) {
        return new VetoRequest(
                "system",
                "",
                List.of(),
                ProviderType.DEEPSEEK,
                "model",
                "key",
                new LlmOptions(null, null, 4096, null),
                messages,
                null,
                null);
    }

    @Test
    void assignsTheBatchDeltaOnlyToItsLastRecordAndPreservesItOnRetry() {
        ContextUsageTracker tracker = new ContextUsageTracker();
        var first = request(List.of(ChatMessage.user("one")));
        var second =
                request(
                        List.of(
                                ChatMessage.user("one"),
                                ChatMessage.assistant("answer"),
                                ChatMessage.user("two")));
        assertFalse(
                tracker.measure(first, new LlmSystemUsage.Usage(100, 10), 2)
                        .containsKey("recordDelta"));
        TurnRecord last = TurnRecord.userPrompt(4, "two");
        var measurement = tracker.measure(second, new LlmSystemUsage.Usage(125, 8), 4);
        TurnRecord updated = RecordUsage.add(last, measurement);
        assertEquals(Long.valueOf(25), (Object) RecordTokenCounter.count(updated.payload()));
        assertEquals(3, updated.payload().get("tokenDeltaFromTurn"));
        assertNull(RecordTokenCounter.count(TurnRecord.assistantResponse(3, "answer").payload()));
        updated =
                RecordUsage.add(
                        updated, tracker.measure(second, new LlmSystemUsage.Usage(125, 9), 4));
        assertEquals(Long.valueOf(25), (Object) RecordTokenCounter.count(updated.payload()));
        tracker.reset();
        assertFalse(
                tracker.measure(second, new LlmSystemUsage.Usage(80, 1), 7)
                        .containsKey("recordDelta"));
    }

    @Test
    void discardsHistoricalEstimatesAndNeverEstimatesNewOrRestoredRecords() {
        TurnRecord estimated =
                new TurnRecord(
                        1,
                        TurnType.USER_PROMPT,
                        Map.of(
                                "content",
                                "hello",
                                "usedTokens",
                                12,
                                "tokenCountSource",
                                "estimated"),
                        null);
        assertNull(RecordTokenCounter.count(estimated.payload()));
        assertFalse(
                RecordTokenCounter.withoutEstimate(estimated).payload().containsKey("usedTokens"));
        assertNull(RecordTokenCounter.count(RecordTokenCounter.unmeasured(estimated).payload()));
    }
}
