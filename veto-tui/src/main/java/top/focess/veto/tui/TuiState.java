package top.focess.veto.tui;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import top.focess.veto.client.core.ClientSession;
import top.focess.veto.client.core.ClientView;
import top.focess.veto.client.core.StyleToken;
import top.focess.veto.client.core.StyledText;
import top.focess.veto.client.core.Theme;
import top.focess.veto.contract.IpcFrame;

/**
 * Thread-confined state container for the TUI client: session metadata, logs, scroll offsets, the
 * command-line buffer, and autocomplete UI. All mutations happen on the single event-loop thread,
 * so there is no locking.
 *
 * <p>The interaction protocol (request pipeline, frame dispatch, session-meta application) lives in
 * {@link ClientSession}; this class is a pure {@link ClientView} — the session drives it via
 * callbacks, and {@code VetoTui} owns the session↔connection wiring (submit/cancel/send).
 */
public final class TuiState implements ClientView {

    public enum ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    private final Theme theme;

    private ConnectionState connection = ConnectionState.CONNECTING;
    private String serverAddress = "tcp://127.0.0.1:5555";

    // Session context (cached from ClientSession.onMetaChanged for header rendering)
    private String username = null;
    private int turnCount = 0;
    private String sessionId = null;

    // Command line buffer
    private final StringBuilder commandBuffer = new StringBuilder();
    private int cursorIndex = 0;

    // The server prompt currently being presented (cached: set on onPrompt, cleared on onRunning).
    private IpcFrame.Prompt activePrompt = null;

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

    public TuiState(@NotNull Theme theme) {
        this.theme = theme;
        appendAnsiText(theme.style(StyleToken.MUTED, "Starting Veto TUI...") + "\n");
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    @NotNull
    public ConnectionState getConnection() {
        return connection;
    }

    public void setConnection(@NotNull ConnectionState connection) {
        this.connection = connection;
    }

    @NotNull
    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(@NotNull String serverAddress) {
        this.serverAddress = serverAddress;
    }

    @Nullable
    public String getUsername() {
        return username;
    }

    @NotNull
    public List<String> getAutocompleteCandidates() {
        return autocompleteCandidates;
    }

    public int getSelectedAutocompleteIndex() {
        return selectedAutocompleteIndex;
    }

    public void setSelectedAutocompleteIndex(int selectedAutocompleteIndex) {
        this.selectedAutocompleteIndex = selectedAutocompleteIndex;
    }

    @NotNull
    public String getOriginalInputBeforeAutocomplete() {
        return originalInputBeforeAutocomplete;
    }

    public void setOriginalInputBeforeAutocomplete(
            @NotNull String originalInputBeforeAutocomplete) {
        this.originalInputBeforeAutocomplete = originalInputBeforeAutocomplete;
    }

    public void clearAutocomplete() {
        this.autocompleteCandidates.clear();
        this.selectedAutocompleteIndex = -1;
        this.originalInputBeforeAutocomplete = "";
    }

    public void applyAutocompleteSelection(@NotNull String val) {
        this.commandBuffer.setLength(0);
        this.commandBuffer.append(val);
        this.cursorIndex = val.length();
    }

    public int getTurnCount() {
        return turnCount;
    }

    @Nullable
    public String getSessionId() {
        return sessionId;
    }

    @NotNull
    public StringBuilder getCommandBuffer() {
        return commandBuffer;
    }

    public int getCursorIndex() {
        return cursorIndex;
    }

    @Nullable
    public IpcFrame.Prompt getActivePrompt() {
        return activePrompt;
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

    // ── ClientView (driven by ClientSession on the event-loop thread) ──────

    @Override
    public void onDelta(@NotNull String content) {
        appendAnsiText(content);
    }

    @Override
    public void onProgress(@NotNull StyledText content) {
        appendAnsiText(theme.style(content.token(), content.text()) + "\n");
    }

    @Override
    public void onPrompt(@NotNull IpcFrame.Prompt prompt) {
        activePrompt = prompt;
        // Clear the in-progress command so the user starts the prompt reply fresh.
        commandBuffer.setLength(0);
        cursorIndex = 0;
    }

    @Override
    public void onError(@NotNull StyledText content) {
        appendAnsiText(theme.style(content.token(), content.text()) + "\n");
    }

    @Override
    public void onTerminate(@NotNull StyledText content) {
        appendAnsiText(theme.style(content.token(), content.text()) + "\n");
        connection = ConnectionState.DISCONNECTED;
    }

    @Override
    public void onMetaChanged(@NotNull ClientSession.SessionMeta meta) {
        username = meta.username();
        turnCount = meta.turnCount();
        sessionId = meta.sessionId();
    }

    @Override
    public void onRunning() {
        activePrompt = null;
    }

    @Override
    public void onCommandDispatched(@NotNull String line) {
        // Fires for both dispatch paths (a line typed at IDLE, and a queued command auto-dispatched
        // from onFrame). Slash-commands are not echoed; only plain-text (agent) prompts are.
        if (!line.startsWith("/")) {
            echoInput(line);
        }
    }

    @Override
    public void onIdle() {
        activePrompt = null;
    }

    // ── Submit presentation (the protocol dispatch is in VetoTui via ClientSession) ──

    /**
     * Echoes the user's input in a framed box with a "thinking…" line, for non-slash commands.
     * Called by {@code VetoTui} before it submits the line to the session.
     *
     * @param cmd the trimmed command text
     */
    public void echoInput(@NotNull String cmd) {
        appendAnsiText(
                "\n"
                        + theme.style(
                                StyleToken.BORDER,
                                "╭─ you ──────────────────────────────────────────────")
                        + "\n");
        appendAnsiText("  " + cmd + "\n");
        appendAnsiText(
                theme.style(
                                StyleToken.BORDER,
                                "╰────────────────────────────────────────────────────")
                        + "\n");
        appendAnsiText(theme.style(StyleToken.MUTED, "  thinking...") + "\n");
    }

    // ── Log Appending and Wrapping ────────────────────────────────────────

    public void appendAnsiText(@Nullable String text) {
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

    @NotNull
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

    public void insertString(@Nullable String str) {
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
}
