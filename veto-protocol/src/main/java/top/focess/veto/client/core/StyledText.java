package top.focess.veto.client.core;

import org.jetbrains.annotations.NotNull;

/**
 * A piece of text paired with the {@link StyleToken} it should be rendered in.
 *
 * <p>The core produces {@code StyledText} values for events whose styling matters (Progress, Error,
 * Terminate); the {@link ClientView} resolves them to a native styled string via its {@link Theme}.
 * Plain streamed content (Delta, Done) is passed through as bare strings.
 */
public record StyledText(@NotNull StyleToken token, @NotNull String text) {

    public static StyledText plain(@NotNull String text) {
        return new StyledText(StyleToken.PLAIN, text);
    }

    public static StyledText muted(@NotNull String text) {
        return new StyledText(StyleToken.MUTED, text);
    }

    public static StyledText error(@NotNull String text) {
        return new StyledText(StyleToken.ERROR, text);
    }

    public static StyledText accent(@NotNull String text) {
        return new StyledText(StyleToken.ACCENT, text);
    }

    public static StyledText success(@NotNull String text) {
        return new StyledText(StyleToken.SUCCESS, text);
    }

    public static StyledText warning(@NotNull String text) {
        return new StyledText(StyleToken.WARNING, text);
    }
}
