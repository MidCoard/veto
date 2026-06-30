package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import org.jspecify.annotations.NonNull;
import top.focess.veto.client.core.StyleToken;
import top.focess.veto.client.core.Theme;

/**
 * {@link Theme} mapping {@link StyleToken}s to Mordant ANSI strings via {@link MordantTerminal}.
 *
 * <p>Mordant detects ANSI capability and downgrades to plain text on dumb terminals, so this theme
 * is safe on any terminal. The {@link StyleToken#PROMPT} token is yellow + bold (the prompted-input
 * marker); {@link #styleBold} layers bold over any token's style (used for the startup banner).
 */
public final class MordantTheme implements Theme {

    private final Terminal terminal;

    public MordantTheme(@NonNull Terminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public @NonNull String style(@NonNull StyleToken token, @NonNull String text) {
        return switch (token) {
            case ACCENT -> MordantTerminal.cyan(terminal, text);
            case MUTED, BORDER -> MordantTerminal.dim(terminal, text);
            case ERROR -> MordantTerminal.red(terminal, text);
            case SUCCESS -> MordantTerminal.green(terminal, text);
            case WARNING -> MordantTerminal.yellow(terminal, text);
            case PROMPT -> MordantTerminal.bold(terminal, MordantTerminal.yellow(terminal, text));
            case PLAIN -> text;
        };
    }

    @Override
    public @NonNull String styleBold(@NonNull StyleToken token, @NonNull String text) {
        return MordantTerminal.bold(terminal, style(token, text));
    }
}
