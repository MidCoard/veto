package top.focess.veto.client.core;

import org.jspecify.annotations.NonNull;

/**
 * Maps a {@link StyleToken} + plain text to a styled, client-native string.
 *
 * <p>Each client application provides its own implementation: the REPL maps tokens to Mordant ANSI
 * strings; the TUI maps tokens to raw ANSI strings consumed by {@code AttributedString.fromAnsi}.
 * Both produce (near-)identical ANSI for the same token, which is what unifies
 * Progress/Error/Prompt styling across the two clients.
 */
public interface Theme {

    /**
     * Styles {@code text} with the given token's canonical color/weight.
     *
     * @param token the semantic style
     * @param text the plain text
     * @return the styled string (ANSI for both clients)
     */
    @NonNull String style(@NonNull StyleToken token, @NonNull String text);

    /**
     * Styles {@code text} with the token's color in bold weight.
     *
     * @param token the semantic style
     * @param text the plain text
     * @return the bold styled string
     */
    @NonNull String styleBold(@NonNull StyleToken token, @NonNull String text);
}
