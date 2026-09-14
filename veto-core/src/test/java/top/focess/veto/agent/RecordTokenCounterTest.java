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
    void retainsContextDeltaAsDiagnosticWithoutAssigningItToAnyRecord() {
        ContextUsageTracker tracker = new ContextUsageTracker();
        var first = request(List.of(ChatMessage.user("one")));
        var second =
                request(
                        List.of(
                                ChatMessage.user("one"),
                                ChatMessage.assistant("answer"),
                                ChatMessage.user("two")));
        assertFalse(
                tracker.measure(first, new LlmSystemUsage.Usage(100, 10))
                        .containsKey("recordDelta"));
        TurnRecord last = TurnRecord.userPrompt(4, "two");
        var measurement = tracker.measure(second, new LlmSystemUsage.Usage(125, 8));
        assertEquals(25L, measurement.get("contextDeltaTokens"));
        assertFalse(measurement.containsKey("recordDelta"));
        assertFalse(measurement.containsKey("fromRecordTurn"));
        TurnRecord updated = RecordUsage.add(last, measurement);
        assertNull(RecordTokenCounter.count(updated.payload()));
        assertFalse(updated.payload().containsKey("tokenDeltaFromTurn"));
        assertNull(RecordTokenCounter.count(TurnRecord.assistantResponse(3, "answer").payload()));
        updated =
                RecordUsage.add(updated, tracker.measure(second, new LlmSystemUsage.Usage(125, 9)));
        assertNull(RecordTokenCounter.count(updated.payload()));
        assertTrue(updated.payload().get("llmUsage") instanceof List<?> calls && calls.size() == 2);
        tracker.reset();
        assertFalse(
                tracker.measure(second, new LlmSystemUsage.Usage(80, 1))
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
