package top.focess.veto.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import top.focess.veto.client.core.ClientSession;
import top.focess.veto.client.core.StyledText;
import top.focess.veto.contract.IpcFrame;

/**
 * Tests that {@link TuiState}'s {@link top.focess.veto.client.core.ClientView} callbacks mutate
 * view state correctly — the wiring that replaces the old {@code processServerFrame}/{@code
 * applyMeta} logic. (The protocol itself is tested in {@code veto-client-core}'s {@code
 * ClientSessionTest}.)
 */
class TuiStateTest {

    private static TuiState newState() {
        return new TuiState(new TuiTheme());
    }

    @Test
    void onDeltaAppendsToLogs() {
        TuiState s = newState();
        s.onDelta("hello world");
        // Delta is a streaming chunk with no trailing newline, so it appends to the last log line
        // rather than starting a new one — assert the content is present, not the line count.
        List<AttributedString> logs = s.getWrappedLogs();
        assertTrue(
                logs.get(logs.size() - 1).toString().contains("hello world"),
                "delta content should be in the logs");
    }

    @Test
    void onPromptSetsActivePromptAndClearsBuffer() {
        TuiState s = newState();
        s.insertString("typing");
        assertNull(s.getActivePrompt());
        s.onPrompt(new IpcFrame.Prompt("password", Map.of()));
        assertNotNull(s.getActivePrompt());
        assertEquals("", s.getCommandBuffer().toString()); // buffer cleared for the fresh reply
    }

    @Test
    void onRunningClearsActivePrompt() {
        TuiState s = newState();
        s.onPrompt(new IpcFrame.Prompt("pw", Map.of()));
        s.onRunning();
        assertNull(s.getActivePrompt());
    }

    @Test
    void onMetaChangedUpdatesSessionContext() {
        TuiState s = newState();
        s.onMetaChanged(new ClientSession.SessionMeta("alice", 5, "sess1"));
        assertEquals("alice", s.getUsername());
        assertEquals(5, s.getTurnCount());
        assertEquals("sess1", s.getSessionId());
    }

    @Test
    void onTerminateSetsDisconnected() {
        TuiState s = newState();
        s.onTerminate(StyledText.muted("bye"));
        assertEquals(TuiState.ConnectionState.DISCONNECTED, s.getConnection());
    }

    @Test
    void echoInputAppendsFramedBox() {
        TuiState s = newState();
        int before = s.getWrappedLogs().size();
        s.echoInput("/login user pass");
        assertTrue(s.getWrappedLogs().size() > before);
    }
}
