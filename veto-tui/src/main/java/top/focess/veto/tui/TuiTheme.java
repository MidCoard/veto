package top.focess.veto.tui;

import org.jetbrains.annotations.NotNull;
import top.focess.veto.client.core.StyleToken;
import top.focess.veto.client.core.Theme;

/**
 * {@link Theme} mapping {@link StyleToken}s to raw ANSI strings, consumed by the TUI via {@link
 * org.jline.utils.AttributedString#fromAnsi} (the existing parse path — the TUI previously embedded
 * these escape codes inline in {@code TuiState}). Produces the same semantic colors the TUI already
 * used (bright-black borders, bright-red errors, …), now driven by the shared palette so Progress /
 * Error / Prompt styling is consistent with the REPL.
 */
public final class TuiTheme implements Theme {

    /** The ASCII ESC character (0x1B), the start of every ANSI control sequence. */
    private static final char ESC = 27;

    @Override
    @NotNull
    public String style(@NotNull StyleToken token, @NotNull String text) {
        return switch (token) {
            case ACCENT -> ansi("36", text);
            case MUTED, BORDER -> ansi("90", text);
            case ERROR -> ansi("91", text);
            case SUCCESS -> ansi("92", text);
            case WARNING -> ansi("93", text);
            case PROMPT -> ansi("93;1", text);
            case PLAIN -> text;
        };
    }

    @Override
    @NotNull
    public String styleBold(@NotNull StyleToken token, @NotNull String text) {
        return ansi("1", style(token, text));
    }

    /** Wraps {@code text} in {@code ESC[<code>m … ESC[0m}. */
    private static String ansi(@NotNull String code, @NotNull String text) {
        return "" + ESC + "[" + code + "m" + text + ESC + "[0m";
    }
}
