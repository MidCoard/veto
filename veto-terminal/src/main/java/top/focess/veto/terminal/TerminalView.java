package top.focess.veto.terminal;

import org.jline.reader.LineReader;
import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.terminal.client.ClientSession;
import top.focess.veto.terminal.client.ClientView;
import top.focess.veto.terminal.client.StyleToken;
import top.focess.veto.terminal.client.StyledText;

/**
 * Renders session updates above the terminal prompt. Owns presentation only; REPL wakeup,
 * termination and status refresh remain explicit callbacks to their lifecycle owners. Callbacks run
 * on the REPL or consumer thread under VetoTerminal's interaction monitor.
 */
final class TerminalView implements ClientView {
    private final @NonNull LineReader reader;
    private final @NonNull MordantRenderer renderer;
    private final @NonNull MordantTheme theme;
    private final @NonNull Runnable refreshPrompt;
    private final @NonNull Runnable terminate;
    private final @NonNull Runnable refreshStatus;
    private final boolean verboseToolTrace = "true".equalsIgnoreCase(System.getenv("VETO_DEBUG"));

    TerminalView(
            @NonNull LineReader reader,
            @NonNull MordantRenderer renderer,
            @NonNull MordantTheme theme,
            @NonNull Runnable refreshPrompt,
            @NonNull Runnable terminate,
            @NonNull Runnable refreshStatus) {
        this.reader = reader;
        this.renderer = renderer;
        this.theme = theme;
        this.refreshPrompt = refreshPrompt;
        this.terminate = terminate;
        this.refreshStatus = refreshStatus;
    }

    @Override
    public void onDelta(@NonNull String content) {
        // User-facing answer: a labeled "veto" box with a cyan (ACCENT) border and normal body
        // text. Explicitly distinct from the "thinking" box (onThought), which is fully dimmed
        // - different label, different border color, different body color. The two streams
        // never blur together, even when only one is present on a turn.
        renderBoxed("veto", StyleToken.ACCENT, StyleToken.PLAIN, content);
    }

    @Override
    public void onThought(@NonNull String content) {
        // Interim reasoning: a labeled "thinking" box, fully dimmed (MUTED border and body) so
        // it recedes behind the cyan "veto" answer box. Same shape as onDelta but different
        // label and different color - the user can tell the two streams apart at a glance.
        renderBoxed("thinking", StyleToken.MUTED, StyleToken.MUTED, content);
    }

    @Override
    public void onToolCall(Delta.@NonNull ToolCall call) {
        // Claude-Code-style indicator: a single muted line announcing the tool + its key arg
        // (the "what is the agent about to do"). Compact so a long sequence of tool calls does
        // not bury the reasoning. The matching result lands in onToolResult right after.
        String summary = summarizeToolCall(call);
        renderer.println(theme.style(StyleToken.MUTED, "  ◆ " + summary));
    }

    @Override
    public void onToolResult(Delta.@NonNull ToolResult result) {
        // Framed observation the model saw. Default: truncate to the first 20 lines + show
        // success/failure marker so a long tool output does not flood the REPL. The user
        // can set VETO_DEBUG=true to render the full body for debugging.
        String body = result.text();
        String marker = result.success() ? "✓" : "✗";
        String header = "  ◇ result " + marker;
        if (!verboseToolTrace) {
            body = truncateForPreview(body, 20);
        }
        renderer.println("");
        renderer.println(theme.style(StyleToken.MUTED, header));
        for (String line : body.split("\n", -1)) {
            renderer.println(
                    theme.style(StyleToken.MUTED, "    ") + theme.style(StyleToken.PLAIN, line));
        }
    }

    @Override
    public void onProgress(@NonNull StyledText content) {
        renderer.println(theme.style(content.token(), content.text()));
    }

    @Override
    public void onPrompt(Frame.@NonNull ServerFrame prompt) {
        refreshPrompt.run();
    }

    @Override
    public void onPromptCleared() {
        refreshPrompt.run();
    }

    @Override
    public void onError(@NonNull StyledText content) {
        renderer.println(theme.style(content.token(), content.text()));
    }

    @Override
    public void onTerminate(@NonNull StyledText content) {
        renderer.println(theme.style(content.token(), content.text()));
        terminate.run();
    }

    @Override
    public void onIdle() {
        refreshStatus.run();
    }

    @Override
    public void onRunning() {
        refreshStatus.run();
    }

    @Override
    public void onCommandDispatched(@NonNull String line) {
        // Fires on whichever thread performed the dispatch: the main thread (a line typed at
        // IDLE) or the consumer thread (a queued command auto-dispatched from onFrame).
        // printAbove is thread-safe, so both paths render uniformly.
        if (!line.startsWith("/")) {
            echoInput(line);
            renderer.println(theme.style(StyleToken.MUTED, "  thinking…"));
        }
    }

    @Override
    public void onMetaChanged(ClientSession.@NonNull SessionMeta meta) {
        refreshStatus.run();
    }

    /**
     * Renders a labeled, bordered block - the explicit visual distinction between the dimmed
     * "thinking" stream (interim reasoning) and the normal "veto" message stream (user-facing
     * answer). Each block carries a label and a border so the two streams never blur into one
     * undifferentiated run of text, even when only one is present on a given turn.
     *
     * @param label the header label (e.g. "thinking" or "veto")
     * @param border the style for the box border (top, left rule, bottom)
     * @param body the style for the content lines
     * @param content the text to render (may contain newlines)
     */
    private void renderBoxed(
            @NonNull String label,
            @NonNull StyleToken border,
            @NonNull StyleToken body,
            @NonNull String content) {
        renderer.println("");
        renderer.println(theme.style(border, "  ╭─ " + label + " " + "─".repeat(20)));
        for (String line : content.split("\n", -1)) {
            renderer.println(theme.style(border, "  │ ") + theme.style(body, line));
        }
        renderer.println(theme.style(border, "  ╰" + "─".repeat(24)));
    }

    /**
     * Renders a one-line summary of a tool call for the {@code onToolCall} indicator. Prefers a
     * small set of common "primary" arg keys (path/command/url/...) so the user sees what the agent
     * is about to operate on, falling back to a generic {@code tool(k=v, k2=v2)} for unknown tools.
     * Empty args reduce to just the tool name.
     */
    private static @NonNull String summarizeToolCall(Delta.@NonNull ToolCall call) {
        String tool = call.tool();
        var args = call.args();
        if (args.isEmpty()) {
            return tool;
        }
        String[] preferred = {
            "absolutePath",
            "path",
            "directoryPath",
            "file",
            "filePath",
            "command",
            "url",
            "query",
            "input"
        };
        for (String key : preferred) {
            var v = args.get(key);
            if (v != null) {
                return tool
                        + "("
                        + key
                        + "="
                        + abbreviate(v.isTextual() ? v.asText() : v.toString(), 80)
                        + ")";
            }
        }
        StringBuilder sb = new StringBuilder(tool).append("(");
        boolean first = true;
        for (var e : args.properties()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(abbreviate(String.valueOf(e.getValue()), 40));
            first = false;
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Truncates a string with a trailing ellipsis if it exceeds the cap, otherwise returns as-is.
     */
    private static @NonNull String abbreviate(@NonNull String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /**
     * Truncates a multi-line body to the first {@code maxLines} lines and appends a "…(N more
     * lines)" hint so the user knows the preview was cut. Returns the input unchanged if it already
     * fits.
     */
    private static @NonNull String truncateForPreview(@NonNull String body, int maxLines) {
        String[] lines = body.split("\n", -1);
        if (lines.length <= maxLines) {
            return body;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        sb.append("\n…(").append(lines.length - maxLines).append(" more lines)");
        return sb.toString();
    }

    /**
     * Echoes the user's input wrapped in a clean, framed box for visual clarity.
     *
     * @param line the input text
     */
    private void echoInput(@NonNull String line) {
        int termWidth = reader.getTerminal().getWidth();
        int maxWidth = termWidth > 0 ? Math.max(0, termWidth - 4) : 76;
        int borderLen = Math.max(0, Math.min(maxWidth, line.length() + 4));
        renderer.println("");
        renderer.println(theme.style(StyleToken.BORDER, "  ╭─ you " + "─".repeat(borderLen)));
        renderer.println("  │ " + line);
        renderer.println(
                theme.style(
                        StyleToken.BORDER, "  ╰" + "─".repeat(Math.min(maxWidth, borderLen + 4))));
    }
}
