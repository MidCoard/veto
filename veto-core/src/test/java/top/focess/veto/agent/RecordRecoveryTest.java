package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.ToolCall;

class RecordRecoveryTest {
    @Test
    void unfinishedPromptAndPartialToolBatchRequireExplicitContinuation() {
        var prompt = TurnRecord.userPrompt(1, "Work");
        var call = TurnRecord.toolCall(2, new ToolCall("write_file", Map.of(), "a"));
        var result =
                new TurnRecord(
                        3, TurnType.TOOL_RESPONSE, Map.of("call_id", "a", "content", "done"), null);
        var next = TurnRecord.toolCall(4, new ToolCall("write_file", Map.of(), "b"));
        assertTrue(RecordRecovery.requiresExplicitContinuation(List.of(prompt)));
        assertTrue(
                RecordRecovery.requiresExplicitContinuation(List.of(prompt, call, result, next)));
        assertTrue(RecordRecovery.requiresExplicitContinuation(List.of(prompt, call, result)));
        var answer =
                new TurnRecord(5, TurnType.ASSISTANT_RESPONSE, Map.of("content", "Finished"), null);
        assertFalse(
                RecordRecovery.requiresExplicitContinuation(List.of(prompt, call, result, answer)));
    }

    @Test
    void rewindAndGuidedSourceDoNotPretendExecutionFinished() {
        var prompt = TurnRecord.userPrompt(1, "Work");
        var guide =
                new TurnRecord(
                        2, TurnType.ASSISTANT_THOUGHT, Map.of("response", "{\"guide\":{}}"), null);
        var rewind = new TurnRecord(3, TurnType.REWIND, Map.of("record_index", 0), null);
        assertTrue(RecordRecovery.requiresExplicitContinuation(List.of(prompt, guide, rewind)));
        assertTrue(RecordRecovery.requiresExplicitContinuation(List.of(guide)));
        var interrupted =
                new TurnRecord(4, TurnType.EXECUTION_ERROR, Map.of("outcome", "INTERRUPTED"), null);
        assertTrue(
                RecordRecovery.requiresExplicitContinuation(List.of(prompt, guide, interrupted)));
        assertFalse(RecordRecovery.requiresExplicitContinuation(List.of()));
    }
}
