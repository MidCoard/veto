package top.focess.veto.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

/**
 * Thread-safe wrapper or thread-confined state container for the TUI client. Holds session details,
 * logs, scroll offsets, and the current command line buffer. All mutations are performed
 * sequentially inside the main event loop thread, avoiding lock contention.
 */
public final class TuiState {

    public enum ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    private ConnectionState connection = ConnectionState.CONNECTING;
    private String serverAddress = "tcp://127.0.0.1:5555";

    // Session context
    private String username = null;
    private int turnCount = 0;
    private String sessionId = null;

    // Command line buffer
    private final StringBuilder commandBuffer = new StringBuilder();
    private int cursorIndex = 0;

    // Server requests & prompts
    private IpcFrame.Prompt activePrompt = null;
    private final List<String> pendingRequests = new ArrayList<>();
    private boolean awaitingResponse = false;

    // Log history & Scrolling
    private final List<AttributedString> outputLogs = new ArrayList<>();
    private final List<AttributedString> wrappedLogsCache = new ArrayList<>();
    private boolean cacheValid = false;
    private int scrollOffset = 0; // 0 means bottom, > 0 means scrolled up
    private int terminalWidth = 80;
    private int terminalHeight = 24;

    // Autocomplete Suggestions
    private final List<String> autocompleteCandidates = new ArrayList<>();
    private int selectedAutocompleteIndex = -1;
    private String originalInputBeforeAutocomplete = "";

    public TuiState() {
        // Add a starting log message
        appendAnsiText("\u001B[90mStarting Veto TUI...\u001B[0m\n");
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public ConnectionState getConnection() {
        return connection;
    }

    public void setConnection(ConnectionState connection) {
        this.connection = connection;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public String getUsername() {
        return username;
    }

    public List<String> getAutocompleteCandidates() {
        return autocompleteCandidates;
    }

    public int getSelectedAutocompleteIndex() {
        return selectedAutocompleteIndex;
    }

    public void setSelectedAutocompleteIndex(int selectedAutocompleteIndex) {
        this.selectedAutocompleteIndex = selectedAutocompleteIndex;
    }

    public String getOriginalInputBeforeAutocomplete() {
        return originalInputBeforeAutocomplete;
    }

    public void setOriginalInputBeforeAutocomplete(String originalInputBeforeAutocomplete) {
        this.originalInputBeforeAutocomplete = originalInputBeforeAutocomplete;
    }

    public void clearAutocomplete() {
        this.autocompleteCandidates.clear();
        this.selectedAutocompleteIndex = -1;
        this.originalInputBeforeAutocomplete = "";
    }

    public void applyAutocompleteSelection(String val) {
        this.commandBuffer.setLength(0);
        this.commandBuffer.append(val);
        this.cursorIndex = val.length();
    }

    public int getTurnCount() {
        return turnCount;
    }

    public String getSessionId() {
        return sessionId;
    }

    public StringBuilder getCommandBuffer() {
        return commandBuffer;
    }

    public int getCursorIndex() {
        return cursorIndex;
    }

    public IpcFrame.Prompt getActivePrompt() {
        return activePrompt;
    }

    public List<String> getPendingRequests() {
        return pendingRequests;
    }

    public boolean isAwaitingResponse() {
        return awaitingResponse;
    }

    public void setAwaitingResponse(boolean awaitingResponse) {
        this.awaitingResponse = awaitingResponse;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public int getTerminalWidth() {
        return terminalWidth;
    }

    public int getTerminalHeight() {
        return terminalHeight;
    }

    // ── Log Appending and Wrapping ────────────────────────────────────────

    public void appendAnsiText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        String[] segments = text.split("\n", -1);

        // Process the first segment: append it to the last line if not empty, or start new if empty
        AttributedString firstSeg = AttributedString.fromAnsi(segments[0]);
        if (outputLogs.isEmpty()) {
            outputLogs.add(firstSeg);
        } else {
            int lastIdx = outputLogs.size() - 1;
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.append(outputLogs.get(lastIdx));
            sb.append(firstSeg);
            outputLogs.set(lastIdx, sb.toAttributedString());
        }

        // Process any subsequent segments as new lines
        for (int i = 1; i < segments.length; i++) {
            outputLogs.add(AttributedString.fromAnsi(segments[i]));
        }

        cacheValid = false;
        // Keep scroll offset at bottom if it was already 0
        if (scrollOffset == 0) {
            // Naturally stays at 0 (bottom)
        }
    }

    public List<AttributedString> getWrappedLogs() {
        if (!cacheValid) {
            wrappedLogsCache.clear();
            // content width is terminalWidth minus margins, borders, and right-hand safety column
            int wrapWidth = Math.max(10, terminalWidth - 5);
            for (AttributedString line : outputLogs) {
                if (line.length() <= wrapWidth) {
                    wrappedLogsCache.add(line);
                } else {
                    int start = 0;
                    while (start < line.length()) {
                        int end = Math.min(start + wrapWidth, line.length());
                        wrappedLogsCache.add(line.subSequence(start, end));
                        start = end;
                    }
                }
            }
            cacheValid = true;
            clampScrollOffset();
        }
        return wrappedLogsCache;
    }

    // ── Scrolling & Size Changes ──────────────────────────────────────────

    public void handleResize(int width, int height) {
        this.terminalWidth = Math.max(20, width);
        this.terminalHeight = Math.max(10, height);
        this.cacheValid = false;
    }

    public void scrollUp(int lines) {
        scrollOffset += lines;
        clampScrollOffset();
    }

    public void scrollDown(int lines) {
        scrollOffset -= lines;
        clampScrollOffset();
    }

    public void clampScrollOffset() {
        int wrappedSize = wrappedLogsCache.size();
        int outputHeight = Math.max(1, terminalHeight - 7);
        int maxScroll = Math.max(0, wrappedSize - outputHeight);
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
    }

    // ── Input Editing ─────────────────────────────────────────────────────

    public void insertChar(char c) {
        clearAutocomplete();
        commandBuffer.insert(cursorIndex, c);
        cursorIndex++;
    }

    public void insertString(String str) {
        if (str == null) return;
        clearAutocomplete();
        commandBuffer.insert(cursorIndex, str);
        cursorIndex += str.length();
    }

    public void backspace() {
        clearAutocomplete();
        if (cursorIndex > 0) {
            commandBuffer.deleteCharAt(cursorIndex - 1);
            cursorIndex--;
        }
    }

    public void cursorLeft() {
        if (cursorIndex > 0) {
            cursorIndex--;
        }
    }

    public void cursorRight() {
        if (cursorIndex < commandBuffer.length()) {
            cursorIndex++;
        }
    }

    public void clearInput() {
        clearAutocomplete();
        commandBuffer.setLength(0);
        cursorIndex = 0;
    }

    // ── Actions Handlers ──────────────────────────────────────────────────

    public IpcFrame.ClientFrame handleSubmit() {
        String cmd = commandBuffer.toString().trim();
        clearAutocomplete();
        commandBuffer.setLength(0);
        cursorIndex = 0;

        if (activePrompt != null) {
            IpcFrame.Input inputFrame = new IpcFrame.Input(cmd);
            activePrompt = null;
            awaitingResponse = true;
            return inputFrame;
        } else {
            if (cmd.isEmpty()) {
                return null;
            }
            if (!cmd.startsWith("/")) {
                // Echo the user input with a nice framed structure
                appendAnsiText(
                        "\n\u001B[90m╭─ you ──────────────────────────────────────────────\u001B[0m\n");
                appendAnsiText("  " + cmd + "\n");
                appendAnsiText(
                        "\u001B[90m╰────────────────────────────────────────────────────\u001B[0m\n");
                appendAnsiText("\u001B[90m  thinking...\u001B[0m\n");
            }
            pendingRequests.add(cmd);
            if (!awaitingResponse) {
                awaitingResponse = true;
                String nextReq = pendingRequests.remove(0);
                return new IpcFrame.Request(nextReq);
            }
            return null;
        }
    }

    public IpcFrame.Cancel handleCancel() {
        clearAutocomplete();
        if (awaitingResponse || activePrompt != null) {
            activePrompt = null;
            pendingRequests.clear();
            awaitingResponse = false;
            commandBuffer.setLength(0);
            cursorIndex = 0;
            return new IpcFrame.Cancel();
        }
        return null; // Signals we should shutdown
    }

    // ── Server Frame Event Processor ──────────────────────────────────────

    /**
     * Updates internal state from a server frame.
     *
     * @return a client frame that needs to be sent immediately as a result of this state update, or
     *     null.
     */
    public IpcFrame.ClientFrame processServerFrame(IpcFrame.ServerFrame frame) {
        if (frame instanceof IpcFrame.Welcome) {
            connection = ConnectionState.CONNECTED;
            appendAnsiText("\u001B[92mConnected to backend.\u001B[0m\n");
        } else if (frame instanceof IpcFrame.Delta d) {
            appendAnsiText(d.content());
        } else if (frame instanceof IpcFrame.Progress p) {
            appendAnsiText("\u001B[90m  ⏳ " + p.content() + "\u001B[0m\n");
        } else if (frame instanceof IpcFrame.Prompt pr) {
            activePrompt = pr;
            awaitingResponse = false;
            commandBuffer.setLength(0);
            cursorIndex = 0;
        } else if (frame instanceof IpcFrame.Done done) {
            if (done.content() != null) {
                appendAnsiText(done.content());
            }
            applyMeta(done.meta());
            if (!pendingRequests.isEmpty()) {
                awaitingResponse = true;
                String nextReq = pendingRequests.remove(0);
                return new IpcFrame.Request(nextReq);
            } else {
                awaitingResponse = false;
            }
        } else if (frame instanceof IpcFrame.Error e) {
            if (e.content() != null) {
                appendAnsiText("\u001B[91mError: " + e.content() + "\u001B[0m\n");
            }
            if (!pendingRequests.isEmpty()) {
                awaitingResponse = true;
                String nextReq = pendingRequests.remove(0);
                return new IpcFrame.Request(nextReq);
            } else {
                awaitingResponse = false;
            }
        } else if (frame instanceof IpcFrame.Terminate t) {
            if (t.reason() != null) {
                appendAnsiText("\u001B[91mTerminated: " + t.reason() + "\u001B[0m\n");
            }
            connection = ConnectionState.DISCONNECTED;
        }
        return null;
    }

    public void applyMeta(Map<String, Object> meta) {
        if (meta == null) return;
        if (meta.containsKey(IpcMeta.USERNAME)) {
            username = (String) meta.get(IpcMeta.USERNAME);
        }
        if (meta.containsKey(IpcMeta.TURN_NUMBER)) {
            Number num = (Number) meta.get(IpcMeta.TURN_NUMBER);
            if (num != null) {
                turnCount = num.intValue();
            }
        }
        if (meta.containsKey(IpcMeta.SESSION)) {
            sessionId = (String) meta.get(IpcMeta.SESSION);
        }
        if (Boolean.TRUE.equals(meta.get(IpcMeta.CLEAR_SESSION))) {
            username = null;
            turnCount = 0;
            sessionId = null;
        }
    }
}
