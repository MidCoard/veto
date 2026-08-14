package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.llm.core.ChatMessage;

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
    void toolMessageWithoutCallIdIsDemotedToUserText() {
        List<ChatMessage> window =
                List.of(ChatMessage.user("go"), ChatMessage.tool("synthetic observation"));

        List<ChatMessage> out = PromptCompiler.wellFormed(window, window);

        assertEquals(List.of("user", "user"), sigs(out));
        assertEquals("synthetic observation", out.get(1).content());
    }
}
