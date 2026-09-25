package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.llm.core.*;

class RecordTokenCounterTest {
    private static com.fasterxml.jackson.databind.@NonNull JsonNode json(@NonNull Object value) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value);
    }

    private @NonNull VetoRequest request(@NonNull List<ChatMessage> messages) {
        return new VetoRequest(
                "system",
                "",
                List.of(),
                ProviderType.DEEPSEEK,
                "model",
                "key",
                new LlmOptions(null, null, 4096, null, null),
                messages,
                null,
                true,
                ResponseContract.ordinary());
    }

    @Test
    void storesOnlyRawMeasurementsOutsideContent() {
        var request = request(List.of(ChatMessage.user("one")));
        var usage =
                UsageMeasurement.measured(request, new LlmSystemUsage.Usage(125, 8))
                        .forRequest("call");
        var turn = RecordUsage.add(TurnRecord.userPrompt(1, "one"), usage);
        assertEquals(125, turn.llmUsage().getFirst().inputTokens());
        assertFalse(turn.payload().containsKey("llmUsage"));
        assertFalse(json(usage).has("contextDeltaTokens"));
        assertNull(RecordTokenCounter.count(turn.payload()));
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
