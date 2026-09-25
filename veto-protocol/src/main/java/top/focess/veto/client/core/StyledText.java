package top.focess.veto.client.core;

import org.jspecify.annotations.NonNull;

/**
 * A piece of text paired with the {@link StyleToken} it should be rendered in.
 *
 * <p>The core produces {@code StyledText} values for events whose styling matters (Progress, Error,
 * Terminate); the {@link ClientView} resolves them to a native styled string via its {@link Theme}.
 * Plain streamed content (Delta, Done) is passed through as bare strings.
 */
public record StyledText(@NonNull StyleToken token, @NonNull String text) {

    /** Wraps {@code text} in {@link StyleToken#PLAIN} (default body text, no styling). */
    public static @NonNull StyledText plain(@NonNull String text) {
        return new StyledText(StyleToken.PLAIN, text);
    }

    /** Wraps {@code text} in {@link StyleToken#MUTED} (secondary/dimmed text). */
    public static @NonNull StyledText muted(@NonNull String text) {
        return new StyledText(StyleToken.MUTED, text);
    }

    /** Wraps {@code text} in {@link StyleToken#ERROR} (failure text). */
    public static @NonNull StyledText error(@NonNull String text) {
        return new StyledText(StyleToken.ERROR, text);
    }

    /** Wraps {@code text} in {@link StyleToken#ACCENT} (primary highlight). */
    public static @NonNull StyledText accent(@NonNull String text) {
        return new StyledText(StyleToken.ACCENT, text);
    }

    /** Wraps {@code text} in {@link StyleToken#SUCCESS} (positive outcome). */
    public static @NonNull StyledText success(@NonNull String text) {
        return new StyledText(StyleToken.SUCCESS, text);
    }

    /** Wraps {@code text} in {@link StyleToken#WARNING} (cautious / transitional text). */
    public static @NonNull StyledText warning(@NonNull String text) {
        return new StyledText(StyleToken.WARNING, text);
    }
}
