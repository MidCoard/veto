package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderMessages;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;

class MessageCitationsTest {
    @Test
    void pendingThoughtSourcesSurviveFlushMergeAndTrailingEmission() {
        var compiler =
                PromptCompiler.isolated(
                        new VetoCapabilityTranslator(), new ObjectMapper(), "Read", 8000);
        var messages =
                compiler.resolveRewinds(
                        List.of(
                                TurnRecord.assistantThought(1, "before init"),
                                new TurnRecord(
                                        2,
                                        TurnType.AGENT_INIT,
                                        Map.of("system_prompt", "system"),
                                        null),
                                TurnRecord.assistantThought(3, "before tool"),
                                new TurnRecord(
                                        4,
                                        TurnType.TOOL_CALL,
                                        Map.of(
                                                "call_id",
                                                "call",
                                                "tool_name",
                                                "view_file",
                                                "args",
                                                Map.of()),
                                        null),
                                TurnRecord.toolResponse(5, "call", "result", true),
                                TurnRecord.assistantThought(6, "trailing")),
                        ToolResultPresentationMode.BASIC);

        assertEquals(
                List.of(List.of(1), List.of(), List.of(3, 4), List.of(5), List.of(6)),
                messages.stream().map(ChatMessage::sourceTurns).toList());
        assertEquals("before init", messages.getFirst().content());
        assertEquals("before tool", messages.get(2).content());
        assertEquals("trailing", messages.getLast().content());
    }

    @Test
    void decodedToolTextAndCallArgumentsHaveNavigableJsonPaths() {
        String text = "{\"text/#\":\"line one\\nline two\"}";
        var request =
                request(
                        ProviderType.OPENAI,
                        List.of(ChatMessage.toolResult("call", text).withSourceTurns(List.of(5))));
        var match =
                MessageCitations.bind(
                                request,
                                response(0, "line one\nline two"),
                                List.of(TurnRecord.toolResponse(5, "call", text, true)))
                        .checks()
                        .getFirst()
                        .matches()
                        .getFirst();
        assertEquals("json:[\"content\",\"text/#\"]", match.field());
        assertEquals("line one\nline two", match.excerpt());
        var call =
                ChatMessage.assistantToolCall("call", "tool", text, "", null)
                        .withSourceTurns(List.of(6));
        var record =
                new TurnRecord(
                        6,
                        TurnType.TOOL_CALL,
                        Map.of("args", Map.of("text/#", "line one\nline two")),
                        null);
        var argument =
                MessageCitations.bind(
                        request(ProviderType.OPENAI, List.of(call)),
                        response(0, "line one\nline two"),
                        List.of(record));
        assertEquals(
                "json:[\"args\",\"text/#\"]",
                argument.checks().getFirst().matches().getFirst().field());
    }

    @Test
    void compilerKeepsSourceIdentityThroughRewindSummaryAndOrphanResult() {
        var compiler =
                PromptCompiler.isolated(
                        new VetoCapabilityTranslator(), new ObjectMapper(), "Read", 8000);
        var history =
                List.of(
                        TurnRecord.userPrompt(1, "Old"),
                        TurnRecord.assistantResponse(2, "Dropped"),
                        new TurnRecord(
                                3,
                                TurnType.REWIND,
                                Map.of("record_index", 1, "content", "Recall"),
                                null),
                        new TurnRecord(
                                4, TurnType.COMPACTION_SUMMARY, Map.of("content", "Summary"), null),
                        TurnRecord.toolResponse(5, null, "Orphan observation", true));
        var compiled = compiler.resolveRewinds(history, ToolResultPresentationMode.BASIC);
        assertFalse(compiled.stream().anyMatch(message -> message.content().equals("Dropped")));
        var repaired = PromptCompiler.wellFormed(compiled, compiled);
        var summary =
                repaired.stream()
                        .filter(message -> message.content().equals("Summary"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of(4), summary.sourceTurns());
        assertEquals(List.of(5), repaired.getLast().sourceTurns());
    }

    @Test
    void toolJsonEvidenceRetainsItsActualUtf16SourcePosition() {
        String evidence =
                "{\"evidence\":[{\"quote\":\"前😀 A\\nB 后\",\"url\":\"https://example.com\"}]}";
        var request =
                request(
                        ProviderType.OPENAI,
                        List.of(
                                ChatMessage.toolResult("call", evidence)
                                        .withSourceTurns(List.of(5))));
        var match =
                MessageCitations.bind(
                                request,
                                response(0, "😀 A\nB"),
                                List.of(TurnRecord.toolResponse(5, "call", evidence, true)))
                        .checks()
                        .getFirst()
                        .matches()
                        .getFirst();
        assertEquals("evidence[0].quote", match.field());
        assertEquals(
                "😀 A\nB", match.excerpt().substring(match.highlightStart(), match.highlightEnd()));
        assertEquals(1, match.sourceStart());
    }

    private static @NonNull VetoRequest request(
            @NonNull ProviderType provider, @NonNull List<ChatMessage> messages) {
        return new VetoRequest(
                "system",
                "fallback",
                List.of(),
                provider,
                "model",
                "credential",
                LlmOptions.defaults(),
                messages,
                null,
                null);
    }

    private static @NonNull VetoResponse response(int index, @NonNull String quote) {
        return new VetoResponse(
                null,
                null,
                "[source](cite:one)",
                null,
                List.of(
                        new VetoResponse.Citation(
                                "one", List.of(new VetoResponse.Source(index, quote)))));
    }

    @ParameterizedTest
    @EnumSource(ProviderType.class)
    void excludesSystemAndBindsZeroToTheFirstActualMessage(@NonNull ProviderType provider) {
        var request =
                request(
                        provider,
                        List.of(
                                ChatMessage.system("private"),
                                ChatMessage.user("前😀 meeting at 14:30")
                                        .withSourceTurns(List.of(8))));
        var check =
                MessageCitations.bind(
                                request,
                                response(0, "😀 meeting"),
                                List.of(TurnRecord.userPrompt(8, "前😀 meeting at 14:30")))
                        .checks()
                        .getFirst();
        assertEquals("matched", check.status());
        assertEquals(8, check.matches().getFirst().turn());
        assertEquals(1, check.matches().getFirst().sourceStart());
        assertEquals(11, check.matches().getFirst().sourceEnd());
    }

    @Test
    void neverRepairsAnIncorrectIndexFromAnotherMessageOrRemovedHistory() {
        var history =
                List.of(TurnRecord.userPrompt(1, "meeting"), TurnRecord.userPrompt(2, "different"));
        var request =
                request(
                        ProviderType.OPENAI,
                        List.of(ChatMessage.user("different").withSourceTurns(List.of(2))));
        for (int index : List.of(-1, 0, 1, 20)) {
            var bound = MessageCitations.bind(request, response(index, "meeting"), history);
            assertTrue(bound.checks().getFirst().matches().isEmpty());
            assertEquals("not_found", bound.checks().getFirst().references().getFirst().status());
        }
    }

    @Test
    void mergedAnthropicBlocksShareOneIndexButOtherProvidersKeepMessagesSeparate() {
        var messages =
                List.of(
                        ChatMessage.user("first").withSourceTurns(List.of(1)),
                        ChatMessage.user("second").withSourceTurns(List.of(2)),
                        ChatMessage.assistant(""),
                        ChatMessage.assistant("answer"),
                        ChatMessage.toolResult("call", "result"));
        assertEquals(3, ProviderMessages.groups(request(ProviderType.ANTHROPIC, messages)).size());
        for (var provider :
                List.of(ProviderType.OPENAI, ProviderType.GEMINI, ProviderType.DEEPSEEK))
            assertEquals(5, ProviderMessages.groups(request(provider, messages)).size());
        var check =
                MessageCitations.bind(
                                request(ProviderType.ANTHROPIC, messages),
                                response(0, "second"),
                                List.of(
                                        TurnRecord.userPrompt(1, "first"),
                                        TurnRecord.userPrompt(2, "second")))
                        .checks()
                        .getFirst();
        assertEquals(2, check.matches().getFirst().turn());
    }

    @Test
    void preservesMultipleDeclaredSourcesAndReportsPartialFailure() {
        var request =
                request(
                        ProviderType.OPENAI,
                        List.of(
                                ChatMessage.user("meeting").withSourceTurns(List.of(1)),
                                ChatMessage.assistant("meeting").withSourceTurns(List.of(2))));
        var response =
                new VetoResponse(
                        null,
                        null,
                        "[source](cite:one)",
                        null,
                        List.of(
                                new VetoResponse.Citation(
                                        "one",
                                        List.of(
                                                new VetoResponse.Source(0, "meeting"),
                                                new VetoResponse.Source(1, "meeting"),
                                                new VetoResponse.Source(9, "meeting")))));
        var check =
                MessageCitations.bind(
                                request,
                                response,
                                List.of(
                                        TurnRecord.userPrompt(1, "meeting"),
                                        TurnRecord.assistantResponse(2, "meeting")))
                        .checks()
                        .getFirst();
        assertEquals(2, check.matches().size());
        assertEquals(3, check.references().size());
        assertEquals("unavailable", check.status());
    }

    @Test
    void ephemeralRetryMessagesCountButCannotInventDurableSources() {
        var request =
                request(
                        ProviderType.OPENAI,
                        List.of(
                                ChatMessage.user("question").withSourceTurns(List.of(1)),
                                ChatMessage.user("schema correction")));
        var bound =
                MessageCitations.bind(
                        request,
                        response(1, "correction"),
                        List.of(TurnRecord.userPrompt(1, "question")));
        assertEquals(2, bound.messageCount());
        assertEquals("unavailable", bound.checks().getFirst().references().getFirst().status());
    }

    @Test
    void provenanceNeverChangesSerializedContentOrToolArguments() throws Exception {
        var mapper = new ObjectMapper();
        var original = ChatMessage.toolResult("call", "{\"answer\":\"你好\"}");
        assertEquals(
                mapper.writeValueAsString(original),
                mapper.writeValueAsString(original.withSourceTurns(List.of(71))));
    }
}
