package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/**
 * Contract tests for {@link PromptCompiler#wellFormed} — the provider-agnostic conversation shape
 * every strict provider accepts.
 *
 * <p>Regression anchor: MiniMax error 2013 ("invalid params, tool call result does not follow tool
 * call") fired when an episode was cut off mid-tool (user interrupt / backend restart) and the
 * persisted history kept an assistant tool_call with no tool_result, followed by the next user
 * prompt. The invariants must hold for a dangling call ANYWHERE in the window, not only at the
 * tail.
 */
class PromptCompilerWellFormedTest {
    @Test
    void contextUpdateKeepsConversationIdentityAndPutsSystemFirst() {
        var compiler =
                PromptCompiler.isolated(
                        new VetoCapabilityTranslator(), new ObjectMapper(), "instructions", 100000);
        var history =
                new java.util.ArrayList<>(
                        List.of(
                                TurnRecord.agentInit(1, "standalone", "old", "test", "test"),
                                TurnRecord.userPrompt(2, "original message")));
        history.addAll(
                top.focess.veto.agent.HistoryProjection.reinitialize(
                        history, 2, "standalone", "updated", "test", "test"));
        var messages = compiler.resolveRewinds(history, ToolResultPresentationMode.BASIC);
        assertEquals(2, messages.size());
        assertEquals("system", messages.getFirst().role());
        assertEquals("updated", messages.getFirst().content());
        assertEquals("original message", messages.getLast().content());
        assertEquals(3, history.size());
        assertTrue(history.stream().noneMatch(t -> t.payload().containsKey("restored_from_turn")));
    }

    @Test
    void interruptedRepairUsesTheSelectedResultFormatInBothCompilerModes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var translator = new VetoCapabilityTranslator();
        var standard =
                new PromptCompiler(translator, new SystemPromptResolver(), mapper, "PROTECTED");
        ReflectionTestUtils.setField(standard, "maxInputTokens", 100000);
        ReflectionTestUtils.setField(standard, "contextFillRatio", 1.0);
        var isolated = PromptCompiler.isolated(translator, mapper, "Task instructions", 100000);
        var persona = new AgentPersona("test", "Veto", "Test", Set.of());
        var workspace =
                Workspace.single(Path.of(System.getProperty("user.dir", ".")), PathMode.REAL);
        var history =
                List.of(
                        TurnRecord.agentInit(1, "standalone", "Task instructions", "test", "test"),
                        TurnRecord.userPrompt(2, "Read the file"),
                        TurnRecord.toolCall(
                                3,
                                new ToolCall(
                                        "view_file",
                                        Map.of("absolutePath", "/fixture/file"),
                                        "interrupted-call")));
        for (PromptCompiler compiler : List.of(standard, isolated)) {
            var detailed =
                    compiler.compile(
                            persona,
                            workspace,
                            null,
                            history,
                            1.0,
                            ToolResultPresentationMode.DETAILED);
            var result = detailed.messages().getLast();
            var data = mapper.readTree(result.content());
            assertEquals("interrupted", data.path("status").asText());
            assertEquals("plaintext", data.path("format").asText());
            assertEquals(PromptCompiler.INTERRUPTED_TOOL_RESULT, data.path("content").asText());
            assertEquals("TOOL_RESULT_MISSING", data.path("errorCode").asText());
            assertEquals(4, data.size());
            assertEquals("interrupted-call", result.callId());
            assertEquals(Boolean.FALSE, result.toolSuccess());
            var basic =
                    compiler.compile(
                            persona,
                            workspace,
                            null,
                            history,
                            1.0,
                            ToolResultPresentationMode.BASIC);
            assertEquals(
                    PromptCompiler.INTERRUPTED_TOOL_RESULT, basic.messages().getLast().content());
        }
    }

    @Test
    void failedToolResponseKeepsItsStatusForProviderAdapters() {
        ChatMessage message =
                PromptCompiler.mapToolResponse(
                        TurnRecord.toolResponse(3, "call_A", "timed out", false));

        assertEquals("tool", message.role());
        assertEquals("call_A", message.callId());
        assertEquals(Boolean.FALSE, message.toolSuccess());
        assertEquals("timed out", message.toolResultContentWithStatus());
        var observation =
                PromptCompiler.mapToolResponse(
                        TurnRecord.toolResponse(4, null, "timed out", false));
        assertEquals("user", observation.role());
        assertEquals(Boolean.FALSE, observation.toolSuccess());
    }

    private static @NonNull ChatMessage call(@NonNull String callId, @NonNull String tool) {
        return ChatMessage.assistantToolCall(callId, tool, "{}", "", null);
    }

    private static @NonNull ChatMessage result(@NonNull String callId, @NonNull String content) {
        return ChatMessage.toolResult(callId, content);
    }

    /** role/callId signature of a message, for compact structural assertions. */
    private static @NonNull String sig(@NonNull ChatMessage m) {
        return m.role() + (m.callId() == null ? "" : ":" + m.callId());
    }

    private static @NonNull List<@NonNull String> sigs(
            @NonNull List<@NonNull ChatMessage> messages) {
        return messages.stream().map(PromptCompilerWellFormedTest::sig).toList();
    }

    @Test
    void midConversationDanglingCallGetsSyntheticResult() {
        // The production failure shape: call B was issued, the episode died before its result
        // landed, and the next user prompt continued the conversation.
        List<ChatMessage> window =
                List.of(
                        ChatMessage.user("do the work"),
                        call("call_A", "run_command"),
                        result("call_A", "ok"),
                        call("call_B", "run_command"),
                        ChatMessage.user("next prompt"),
                        call("call_C", "run_task"),
                        result("call_C", "task started"),
                        ChatMessage.user("current prompt"));

        List<ChatMessage> out = PromptCompiler.wellFormed(window, window);

        assertEquals(
                List.of(
                        "user",
                        "assistant:call_A",
                        "tool:call_A",
                        "assistant:call_B",
                        "tool:call_B", // synthesized — the episode never recorded one
                        "user",
                        "assistant:call_C",
                        "tool:call_C",
                        "user"),
                sigs(out));
        // Every tool_call is immediately followed by a tool_result with the same callId.
        for (int i = 0; i < out.size(); i++) {
            ChatMessage m = out.get(i);
            if ("assistant".equals(m.role()) && m.callId() != null) {
                ChatMessage next = out.get(i + 1);
                assertEquals("tool", next.role(), "call " + m.callId() + " must be answered");
                assertEquals(m.callId(), next.callId());
            }
        }
    }

    @Test
    void trailingDanglingCallGetsSyntheticResult() {
        List<ChatMessage> window = List.of(ChatMessage.user("go"), call("call_A", "view_file"));

        List<ChatMessage> out = PromptCompiler.wellFormed(window, window);

        assertEquals(List.of("user", "assistant:call_A", "tool:call_A"), sigs(out));
        assertTrue(out.get(2).content().contains("interrupt"));
    }

    @Test
    void pairedConversationPassesThroughUnchanged() {
        List<ChatMessage> window =
                List.of(
                        ChatMessage.user("go"),
                        call("call_A", "list_dir"),
                        result("call_A", "a/ b/"),
                        ChatMessage.assistant("done"));

        List<ChatMessage> out = PromptCompiler.wellFormed(window, window);

        assertEquals(List.of("user", "assistant:call_A", "tool:call_A", "assistant"), sigs(out));
        assertEquals("a/ b/", out.get(2).content());
    }

    @Test
    void orphanedToolResultIsDemotedToUserText() {
        // The budget window can open on a bare tool_result whose call was trimmed. Demotion keeps
        // the content as user text, which already satisfies the opens-on-user invariant.
        List<ChatMessage> window = List.of(result("call_A", "a/ b/"), ChatMessage.user("current"));

        List<ChatMessage> out = PromptCompiler.wellFormed(window, window);

        assertEquals(List.of("user", "user"), sigs(out));
        assertEquals("a/ b/", out.get(0).content());
        assertEquals("current", out.get(1).content());
    }

    @Test
    void windowOpeningOnAssistantIsReAnchoredOnUserPrompt() {
        List<ChatMessage> full =
                List.of(
                        ChatMessage.user("first prompt"),
                        ChatMessage.assistant("first answer"),
                        ChatMessage.user("second prompt"));
        List<ChatMessage> window = List.of(ChatMessage.assistant("first answer"));

        List<ChatMessage> out = PromptCompiler.wellFormed(full, window);

        assertEquals("user", out.get(0).role());
        assertEquals("second prompt", out.get(0).content());
    }

    @Test
    void runtimeInstructionsAndObservationsDoNotReplaceTheDirectRequestAnchor() {
        var request = ChatMessage.user("Original bounded request").withSourceTurns(List.of(1));
        var note =
                PromptLibrary.message(
                        "runtime-execution-error", Map.of("error", "Temporary execution failure"));
        var observation = new ChatMessage("user", "Tool observation", null, null, null, null, true);
        List<ChatMessage> full =
                List.of(request, note, observation, ChatMessage.assistant("Result"));
        var trimmed = List.of(ChatMessage.assistant("Result"));
        assertEquals(request, PromptCompiler.wellFormed(full, trimmed).getFirst());
        // Preserve the historical fallback for isolated invocations with only runtime-authored
        // input.
        assertEquals(note, PromptCompiler.wellFormed(List.of(note), trimmed).getFirst());
    }

    @Test
    void toolMessageWithoutCallIdIsDemotedToUserText() {
        List<ChatMessage> window =
                List.of(ChatMessage.user("go"), ChatMessage.tool("synthetic observation"));

        List<ChatMessage> out = PromptCompiler.wellFormed(window, window);

        assertEquals(List.of("user", "user"), sigs(out));
        assertEquals("synthetic observation", out.get(1).content());
    }
}
