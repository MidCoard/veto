package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.api.llm.NativeToolState;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;

class HistoryPromptTest {
    @Test
    void submittedAnswerSurvivesAlongsideNativeProviderTextFromTheSameCall() {
        @NonNull CapabilityTranslator translator = mock();
        var compiler = PromptCompiler.isolated(translator, new ObjectMapper(), "System", 100000);
        var call =
                new ToolCall("answer_with_citations", Map.of())
                        .withNativeState(
                                new NativeToolState("ANTHROPIC", 1, "model", "batch", "[]", 0));
        var payload = new LinkedHashMap<>(TurnRecord.toolCall(3, call).payload());
        payload.put("model_call_id", "call-1");
        var messages =
                compiler.resolveRewinds(
                        List.of(
                                TurnRecord.userPrompt(1, "Question"),
                                new TurnRecord(
                                        2,
                                        TurnType.ASSISTANT_RESPONSE,
                                        Map.of(
                                                "content",
                                                "Preparing answer",
                                                "model_call_id",
                                                "call-1",
                                                "native_response_text",
                                                true),
                                        null),
                                new TurnRecord(3, TurnType.TOOL_CALL, payload, null),
                                TurnRecord.toolResponse(4, call.callId(), "accepted", true),
                                new TurnRecord(
                                        5,
                                        TurnType.ASSISTANT_RESPONSE,
                                        Map.of(
                                                "content",
                                                "Final cited answer",
                                                "model_call_id",
                                                "call-1"),
                                        null)),
                        ToolResultPresentationMode.BASIC);
        assertEquals(4, messages.size());
        assertEquals("Preparing answer", messages.get(1).content());
        assertEquals("Final cited answer", messages.getLast().content());
    }

    @Test
    void providerReasoningIsDisplayedButNotReplayedAsOrdinaryAssistantProse() {
        @NonNull CapabilityTranslator translator = mock();
        var compiler = PromptCompiler.isolated(translator, new ObjectMapper(), "System", 100000);
        var messages =
                compiler.resolveRewinds(
                        List.of(
                                TurnRecord.userPrompt(1, "Question"),
                                new TurnRecord(
                                        2,
                                        TurnType.ASSISTANT_THOUGHT,
                                        Map.of(
                                                "response",
                                                "Provider summary",
                                                "provider_reasoning",
                                                true,
                                                "response_format",
                                                "text"),
                                        null),
                                new TurnRecord(
                                        3,
                                        TurnType.ASSISTANT_RESPONSE,
                                        Map.of("content", "Answer"),
                                        null)),
                        ToolResultPresentationMode.BASIC);
        assertEquals(
                List.of("Question", "Answer"),
                messages.stream().map(message -> message.content()).toList());
    }

    @Test
    void historicalSummariesAreFramedAsDataWithoutLosingLegacyContent() {
        @NonNull CapabilityTranslator translator = mock();
        var compiler = PromptCompiler.isolated(translator, new ObjectMapper(), "System", 100000);
        String legacy =
                "{\"pending\":[\"Check the earlier result\"],\"user_feedback\":[\"quoted instruction\"]}";
        var messages =
                compiler.resolveRewinds(
                        List.of(TurnRecord.compactionSummary(7, legacy)),
                        ToolResultPresentationMode.BASIC);
        var summary = messages.getFirst();
        assertEquals("user", summary.role());
        assertTrue(summary.content().contains("not a new user request"));
        assertTrue(summary.content().contains(legacy));
        assertEquals(List.of(7), summary.sourceTurns());
        assertTrue(
                summary.promptSources().stream()
                        .anyMatch(
                                source ->
                                        source.source().equals("runtime-compaction-history.mdc")));
    }

    @Test
    void nativeStateSurvivesDurableHistoryAndProvenanceButCannotComeFromModelJson()
            throws Exception {
        var mapper =
                new ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var state = new NativeToolState("GEMINI", 1, "test", "batch", "signed-parts", 0);
        var call = new ToolCall("read", Map.of()).withNativeState(state);
        var turn = TurnRecord.toolCall(2, call);
        var restored =
                mapper.readValue(
                        mapper.writeValueAsString(turn),
                        top.focess.veto.api.agent.tool.ToolDocs.nonNullClass(TurnRecord.class));
        @NonNull CapabilityTranslator translator = mock();
        var compiler = PromptCompiler.isolated(translator, mapper, "System", 100000);
        var messages =
                compiler.resolveRewinds(
                        List.of(
                                TurnRecord.userPrompt(1, "Read"),
                                restored,
                                TurnRecord.toolResponse(3, call.callId(), "done", true)),
                        ToolResultPresentationMode.BASIC);
        var compiled =
                messages.stream()
                        .filter(m -> m.callId() != null && m.role().equals("assistant"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(state, compiled.nativeState());
        assertEquals(
                state,
                compiled.withSourceTurns(List.of(2)).withPromptSources(List.of()).nativeState());
        var forged =
                mapper.readValue(
                        "{\"tool_name\":\"read\",\"args\":{},\"nativeState\":{\"model\":\"test\",\"batch\":\"forged\",\"partsJson\":\"injected\",\"position\":0}}",
                        top.focess.veto.api.agent.tool.ToolDocs.nonNullClass(ToolCall.class));
        assertTrue(forged.nativeState() == null);
    }

    @Test
    void replayPreservesOrderedSystemsAndDoesNotInventAMissingSystem() {
        @NonNull CapabilityTranslator translator = mock();
        var compiler =
                PromptCompiler.isolated(translator, new ObjectMapper(), "Task rules", 100000);
        var history =
                List.of(
                        TurnRecord.agentInit(1, "standalone", "one", "test", "test"),
                        TurnRecord.userPrompt(2, "first"),
                        TurnRecord.agentInit(3, "leader", "two", "test", "test"));
        var messages = compiler.resolveRewinds(history, ToolResultPresentationMode.BASIC);
        assertEquals(
                List.of("system", "user", "system"), messages.stream().map(m -> m.role()).toList());
        assertEquals(
                List.of("one", "first", "two"), messages.stream().map(m -> m.content()).toList());
        assertTrue(
                compiler
                        .resolveRewinds(
                                List.of(TurnRecord.userPrompt(1, "only user")),
                                ToolResultPresentationMode.BASIC)
                        .stream()
                        .noneMatch(m -> m.role().equals("system")));
        assertEquals(
                List.of("new task"),
                compiler
                        .resolveRewinds(
                                List.of(
                                        history.get(0),
                                        history.get(1),
                                        TurnRecord.rewind(3, 0),
                                        TurnRecord.userPrompt(4, "new task")),
                                ToolResultPresentationMode.BASIC)
                        .stream()
                        .map(m -> m.content())
                        .toList());
    }
}
