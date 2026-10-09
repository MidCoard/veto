package top.focess.veto.terminal.client;

import org.jspecify.annotations.NonNull;

/** Maps terminal semantic styles to ANSI strings through the active renderer. */
public interface Theme {

    /**
     * Styles {@code text} with the given token's canonical color/weight.
     *
     * @param token the semantic style
     * @param text the plain text
     * @return the styled ANSI string
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
